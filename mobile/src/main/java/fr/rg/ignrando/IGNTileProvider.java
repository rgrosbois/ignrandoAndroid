package fr.rg.ignrando;


import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileProvider;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import fr.rg.ignrando.util.GeoLocation;


/**
 * Fournisseur de tuiles pour la surcouche IGN.
 * <p/>
 * <p/>
 * Toutes les tuiles sont dérivées des niveaux 15 et 16 de la base de données
 * IGN , ceux-ci correspondant aux scan de cartes 1:25000.
 */
public class IGNTileProvider implements TileProvider {
    private HashMap<String, Lock> locks = new HashMap<String, Lock>();

    // Clé de développement IGN pour le service WMTS
    private final String cleIGNWeb;
    // Dimension d'une tuile
    public static final int TILE_PIXEL_DIM = 256;
    // Taille du tampon de lecture/écriture
    public static final int BUFFER_SIZE = 16 * 1024;
    // Types de tuiles (vues aérienne)
    private boolean orthoimage;
    // Cache disque pour les tuiles
    private File cacheDir;
    // pinceaux pour dessiner les tuiles
    Paint pText = new Paint();
    Paint pGraph = new Paint();
    private boolean highResolution;

    public IGNTileProvider(Context context) {
        // Activer le cache disque sur support externe (de préférence)
        String cachePath;
        if (Environment.MEDIA_MOUNTED.equals(Environment
                .getExternalStorageDirectory())
                || !Environment.isExternalStorageRemovable()) {
            cachePath = context.getExternalCacheDir().getPath();
        } else { // Stockage en interne
            cachePath = context.getCacheDir().getPath();
        }
        cacheDir = new File(cachePath + File.separator + "ignmaps" + File.separator);
        if (!cacheDir.exists()) {
            cacheDir.mkdir();
        }

        pGraph.setStyle(Paint.Style.STROKE);
        pGraph.setStrokeWidth(3);
        pText.setAntiAlias(true);
        pText.setFakeBoldText(true);
        pText.setTextSize(12);

        WindowManager wm = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        if (metrics.densityDpi < 480) {
            highResolution = false;
        } else {
            highResolution = true;
        }

        // Récupérer la clé IGN
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        cleIGNWeb = settings.getString("IGN_DEVELOPMENT_KEY",
                "ry9bshqmzmv1gao9srw610oq"); // -> novembre 2017 ?
    }

    /**
     * Fournir les données correspondant à une tuile. Les tuiles ont des
     * dimensions de 256x256 sur les périphériques de faible densité et 512x512
     * sur ceux de grande densité.
     */
    @Override
    public Tile getTile(int c, int r, int ignScale) {
        byte[] image;
        double factor = 1;

        switch (ignScale) {
            case 17:
                image = createTileFromLowerScale(c, r, ignScale);
                break;
            case 16:
                image = readRealTileImage(c, r, ignScale);
                break;
            case 15:
                if (highResolution) {
                    image = createHighResZ15Tile(c, r);
                    factor = 2;
                } else {
                    image = readRealTileImage(c, r, 15);
                }
                break;
            case 14:
            case 13:
                image = createTileFromUpperScale(c, r, ignScale);
                factor = Math.pow(2, 15 - ignScale);
                break;
            case 12:
                image = createTileFromUpperScale(c, r, ignScale);
                factor = Math.pow(2, 15 - ignScale);
            default:
                image = createEmptyTile(c, r, ignScale);
                factor = highResolution ? 2 : 1;
                break;
        }

        if (image == null) {
            return NO_TILE;
        } else {
            Tile t = new Tile((int) (factor * TILE_PIXEL_DIM),
                    (int) (factor * TILE_PIXEL_DIM), image);
            return t;
        }

    }

    /**
     * Récupérer les données d'une tuile existant dans la base de données IGN.
     * Deux cas peuvent se présenter:
     * <ol>
     * <li>La tuile est déjà présente dans le cache disque: lire les données
     * depuis le disque.</li>
     * <li>La tuile ne se trouve pas encore dans le cache disque: télécharger les
     * données puis les écrire dans le cache.</li>
     * </ol>
     * Un mécanisme de verrous (un pour chaque tuile) est mis en place afin
     * d'éviter des résultats aléatoires lorsque cette méthode est appelée pour
     * une même tuile par plusieurs fils d'exécution distincts.
     *
     * @param c        Indice de colonne.
     * @param r        Indice de ligne.
     * @param ignScale Niveau de zoom.
     * @return Tableau d'octet contenant les données (compressées) de la tuile.
     */
    public byte[] readRealTileImage(int c, int r, int ignScale) {
        // +--------------------------------+
        // | Acquérir le verrou de la tuile |
        // +--------------------------------+
        String baseName = (orthoimage ? "ortho-" : "") + "z" + ignScale + "-r" + r
                + "-c" + c;
        Lock tLock;
        synchronized (this) {
            tLock = locks.get(baseName);
            if (locks.get(baseName) == null) {
                locks.put(baseName, new ReentrantLock());
                tLock = locks.get(baseName);
            }
        }
        tLock.lock();

        InputStream in = null;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        FileOutputStream out = null;
        boolean notInCache = true;

        File cacheFile = new File(cacheDir, baseName + ".jpg");

        try {
            if (cacheFile.exists()) { // Récupérer depuis le cache disque
                in = new FileInputStream(cacheFile);
                notInCache = false;
            } else { // Télécharger les données
//                Log.d(MainActivity.DEBUG_TAG, "Download r=" + r + ", c=" + c + ", " +
//                        "z=" + ignScale);
                URL url = new URL("http://gpp3-wxs.ign.fr/"
                        + cleIGNWeb
                        + "/wmts/?"
                        + "SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
                        + (orthoimage ? "&LAYER=ORTHOIMAGERY.ORTHOPHOTOS"
                        : "&LAYER=GEOGRAPHICALGRIDSYSTEMS.MAPS") + "&STYLE=normal"
                        + "&TILEMATRIXSET=PM&TILEMATRIX=" + ignScale + "&TILEROW=" + r
                        + "&TILECOL=" + c + "&FORMAT=image/jpeg");
                URLConnection connection = url.openConnection();
                connection.setRequestProperty("Referer", "http://localhost/IGN/");
                in = connection.getInputStream();
            }

            // Lire les octets (du cache disque ou depuis le réseau)
            int nRead;
            byte[] data = new byte[BUFFER_SIZE];
            while ((nRead = in.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }
            in.close();
            buffer.flush();

            if (notInCache) { // Enregistrer l'image dans le cache disque
                out = new FileOutputStream(cacheFile);
                out.write(buffer.toByteArray(), 0, buffer.size());
            }
        } catch (IOException e) {
            e.printStackTrace();

            // +-------------------------------+
            // | Libérer le verrou de la tuile |
            // +-------------------------------+
            if (locks.get(baseName) != null) {
                locks.remove(baseName);
            }
            tLock.unlock();
            return null;
        } finally { // fermer les flux
            if (in != null)
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            if (buffer != null)
                try {
                    buffer.close();
                } catch (Exception ignored) {
                }
            if (out != null)
                try {
                    out.close();
                } catch (Exception ignored) {
                }
        }

        // +-------------------------------+
        // | Libérer le verrou de la tuile |
        // +-------------------------------+
        if (locks.get(baseName) != null) {
            locks.remove(baseName);
        }
        tLock.unlock();
        return buffer.toByteArray();
    }

    /**
     * Créer une tuile vide indiquant son nom
     *
     * @param c        Indice de colonne.
     * @param r        Indice de ligne.
     * @param ignScale Niveau de zoom.
     * @return
     */
    private byte[] createEmptyTile(int c, int r, int ignScale) {
        // Log.d(MainActivity.DEBUG_TAG, "createEmptyTile " + c + "," + r + "," + ignScale);
        // +--------------------------------+
        // | Acquérir le verrou de la tuile |
        // +--------------------------------+
        String baseName = (orthoimage ? "ortho-" : "") + "z" + ignScale + "-r" + r
                + "-c" + c;
        Lock tLock;
        synchronized (this) {
            tLock = locks.get(baseName);
            if (locks.get(baseName) == null) {
                locks.put(baseName, new ReentrantLock());
                tLock = locks.get(baseName);
            }
        }
        tLock.lock();

        // Créer l'image vierge
        Bitmap b;
        if (highResolution) {
            b = Bitmap.createBitmap(2 * TILE_PIXEL_DIM, 2 * TILE_PIXEL_DIM,
                    Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            // Dessiner le cadre
            cv.drawRect(0, 0, 2 * TILE_PIXEL_DIM, 2 * TILE_PIXEL_DIM, pGraph);
            // Placer le texte
            cv.drawText(baseName, 2 * TILE_PIXEL_DIM / 3, TILE_PIXEL_DIM, pText);
        } else {
            b = Bitmap.createBitmap(TILE_PIXEL_DIM, TILE_PIXEL_DIM,
                    Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            // Dessiner le cadre
            cv.drawRect(0, 0, 2 * TILE_PIXEL_DIM, 2 * TILE_PIXEL_DIM, pGraph);
            // Placer le texte
            cv.drawText(baseName, TILE_PIXEL_DIM / 3, TILE_PIXEL_DIM / 2, pText);
        }

        // Générer le tableau d'octets
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, buffer);

        // +-------------------------------+
        // | Libérer le verrou de la tuile |
        // +-------------------------------+
        if (locks.get(baseName) != null) {
            locks.remove(baseName);
        }
        tLock.unlock();
        return buffer.toByteArray();
    }

    /**
     * Créer une nouvelle tuile contenant les 4 tuiles correspondantes du niveau
     * supérieur.
     *
     * @param c        Indice de colonne.
     * @param r        Indice de ligne.
     * @param ignScale Niveau de zoom.
     * @return
     */
    private byte[] createTileFromUpperScale(int c, int r, int ignScale) {
        // Log.d(MainActivity.DEBUG_TAG, "createFromUpper " + c + "," + r + "," + ignScale);
        // +--------------------------------+
        // | Acquérir le verrou de la tuile |
        // +--------------------------------+
        String baseName = (orthoimage ? "ortho-" : "") + "z" + ignScale + "-r" + r
                + "-c" + c;
        Lock tLock;
        synchronized (this) {
            tLock = locks.get(baseName);
            if (locks.get(baseName) == null) {
                locks.put(baseName, new ReentrantLock());
                tLock = locks.get(baseName);
            }
        }
        tLock.lock();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        File cacheFile = new File(cacheDir, baseName + ".jpg");

        if (cacheFile.exists()) { // Récupérer depuis le cache disque
            InputStream in = null;
            try { // Remplir le tampon en lisant le fichier
                in = new FileInputStream(cacheFile);
                int nRead;
                byte[] data = new byte[BUFFER_SIZE];
                while ((nRead = in.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                in.close();
                buffer.flush();

                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                // Retourner le tableau d'octets
                return buffer.toByteArray();
            } catch (IOException e) {
                e.printStackTrace();
                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                return null;
            } finally { // fermer les flux
                if (in != null)
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                if (buffer != null)
                    try {
                        buffer.close();
                    } catch (Exception ignored) {
                    }
            }
        } else {
            // Lire les 4 sous-images
            byte[] img11, img12, img21, img22;
            if (ignScale == 14) {
                img11 = readRealTileImage(2 * c, 2 * r, ignScale + 1);
                img12 = readRealTileImage(2 * c + 1, 2 * r, ignScale + 1);
                img21 = readRealTileImage(2 * c, 2 * r + 1, ignScale + 1);
                img22 = readRealTileImage(2 * c + 1, 2 * r + 1, ignScale + 1);
            } else {
                img11 = createTileFromUpperScale(2 * c, 2 * r, ignScale + 1);
                img12 = createTileFromUpperScale(2 * c + 1, 2 * r, ignScale + 1);
                img21 = createTileFromUpperScale(2 * c, 2 * r + 1, ignScale + 1);
                img22 = createTileFromUpperScale(2 * c + 1, 2 * r + 1, ignScale + 1);
            }

            // Créer la nouvelle image vierge
            Bitmap b;
            Canvas cv;
            int factor;

            factor = 1 << (15 - ignScale);
            b = Bitmap.createBitmap(factor * TILE_PIXEL_DIM, factor * TILE_PIXEL_DIM,
                    Bitmap.Config.ARGB_8888);
            cv = new Canvas(b);

            // Placer les 4 sous-images
            cv.drawBitmap(BitmapFactory.decodeByteArray(img11, 0, img11.length), 0,
                    0, pGraph);
            cv.drawBitmap(BitmapFactory.decodeByteArray(img12, 0, img12.length),
                    factor * TILE_PIXEL_DIM / 2, 0, pGraph);
            cv.drawBitmap(BitmapFactory.decodeByteArray(img21, 0, img21.length), 0,
                    factor * TILE_PIXEL_DIM / 2, pGraph);
            cv.drawBitmap(BitmapFactory.decodeByteArray(img22, 0, img22.length),
                    factor * TILE_PIXEL_DIM / 2, factor * TILE_PIXEL_DIM / 2, pGraph);

            // Remplir le tampon
            b.compress(Bitmap.CompressFormat.JPEG, 100, buffer);
            // if(highResolution && ignScale==15) {
            // Log.d(MainActivity.DEBUG_TAG,
            // "Factor="+factor+", img="+b.getWidth()+"x"+b.getHeight()+", buf="+buffer.size());
            // }
            try { // Sauvegarder l'image dans le cache disque
                b.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(
                        cacheFile));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                return null;
            }

            // +-------------------------------+
            // | Libérer le verrou de la tuile |
            // +-------------------------------+
            if (locks.get(baseName) != null) {
                locks.remove(baseName);
            }
            tLock.unlock();
            // Renvoyer le tableau d'octets
            return buffer.toByteArray();
        }
    }

    /**
     * Créer une nouvelle tuile contenant les 4 tuiles correspondantes du niveau
     * supérieur.
     *
     * @param c Indice de colonne.
     * @param r Indice de ligne.
     * @return
     */
    private byte[] createHighResZ15Tile(int c, int r) {
        // Log.d(MainActivity.DEBUG_TAG, "createFromUpper " + c + "," + r + "," + ignScale);
        // +--------------------------------+
        // | Acquérir le verrou de la tuile |
        // +--------------------------------+
        String baseName = (orthoimage ? "ortho-" : "") + "z15_hr" + "-r" + r + "-c"
                + c;
        Lock tLock;
        synchronized (this) {
            tLock = locks.get(baseName);
            if (locks.get(baseName) == null) {
                locks.put(baseName, new ReentrantLock());
                tLock = locks.get(baseName);
            }
        }
        tLock.lock();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        File cacheFile = new File(cacheDir, baseName + ".jpg");

        if (cacheFile.exists()) { // Récupérer depuis le cache disque
            InputStream in = null;
            try { // Remplir le tampon en lisant le fichier
                in = new FileInputStream(cacheFile);
                int nRead;
                byte[] data = new byte[BUFFER_SIZE];
                while ((nRead = in.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                in.close();
                buffer.flush();

                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                // Retourner le tableau d'octets
                return buffer.toByteArray();
            } catch (IOException e) {
                e.printStackTrace();
                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                return null;
            } finally { // fermer les flux
                if (in != null)
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                if (buffer != null)
                    try {
                        buffer.close();
                    } catch (Exception ignored) {
                    }
            }
        } else {
            // Lire les 4 sous-images
            byte[] img11, img12, img21, img22;
            img11 = readRealTileImage(2 * c, 2 * r, 16);
            img12 = readRealTileImage(2 * c + 1, 2 * r, 16);
            img21 = readRealTileImage(2 * c, 2 * r + 1, 16);
            img22 = readRealTileImage(2 * c + 1, 2 * r + 1, 16);

            // Créer la nouvelle image vierge
            Bitmap b;
            Canvas cv;

            b = Bitmap.createBitmap(2 * TILE_PIXEL_DIM, 2 * TILE_PIXEL_DIM,
                    Bitmap.Config.ARGB_8888);
            cv = new Canvas(b);

            // Placer les 4 sous-images
            cv.drawBitmap(BitmapFactory.decodeByteArray(img11, 0, img11.length), 0,
                    0, pGraph);
            cv.drawBitmap(BitmapFactory.decodeByteArray(img12, 0, img12.length),
                    TILE_PIXEL_DIM, 0, pGraph);
            cv.drawBitmap(BitmapFactory.decodeByteArray(img21, 0, img21.length), 0,
                    TILE_PIXEL_DIM, pGraph);
            cv.drawBitmap(BitmapFactory.decodeByteArray(img22, 0, img22.length),
                    TILE_PIXEL_DIM, TILE_PIXEL_DIM, pGraph);

            // Remplir le tampon
            b.compress(Bitmap.CompressFormat.JPEG, 100, buffer);
            try { // Sauvegarder l'image dans le cache disque
                b.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(
                        cacheFile));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                return null;
            }

            // +-------------------------------+
            // | Libérer le verrou de la tuile |
            // +-------------------------------+
            if (locks.get(baseName) != null) {
                locks.remove(baseName);
            }
            tLock.unlock();
            // Renvoyer le tableau d'octets
            return buffer.toByteArray();
        }
    }

    /**
     * Créer une nouvelle sous-tuile depuis la tuile du niveau inférieur.
     *
     * @param c        Indice de colonne.
     * @param r        Indice de ligne.
     * @param ignScale Niveau de zoom.
     * @return
     */
    private byte[] createTileFromLowerScale(int c, int r, int ignScale) {
        // Log.d(MainActivity.DEBUG_TAG, "createFromLower " + c + "," + r + "," + ignScale);
        // +--------------------------------+
        // | Acquérir le verrou de la tuile |
        // +--------------------------------+
        String baseName = (orthoimage ? "ortho-" : "") + "z" + ignScale + "-r" + r
                + "-c" + c;
        Lock tLock;
        synchronized (this) {
            tLock = locks.get(baseName);
            if (locks.get(baseName) == null) {
                locks.put(baseName, new ReentrantLock());
                tLock = locks.get(baseName);
            }
        }
        tLock.lock();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        File cacheFile = new File(cacheDir, baseName + ".jpg");

        if (cacheFile.exists()) { // Récupérer depuis le cache disque
            InputStream in = null;
            try { // Remplir le tampon en lisant le fichier
                in = new FileInputStream(cacheFile);
                int nRead;
                byte[] data = new byte[BUFFER_SIZE];
                while ((nRead = in.read(data, 0, data.length)) != -1) {
                    buffer.write(data, 0, nRead);
                }
                in.close();
                buffer.flush();

                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                // Retourner le tableau d'octets
                return buffer.toByteArray();
            } catch (IOException e) {
                e.printStackTrace();
                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                return null;
            } finally { // fermer les flux
                if (in != null)
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                if (buffer != null)
                    try {
                        buffer.close();
                    } catch (Exception ignored) {
                    }
            }
        } else {
            // Lire l'image du niveau inférieur
            byte[] imgSup;

            if (ignScale == 17) {
                imgSup = readRealTileImage(c / 2, r / 2, 16);
            } else {
                imgSup = createTileFromLowerScale(c / 2, r / 2, ignScale - 1);
            }

            // Créer la nouvelle image vierge
            Bitmap b = null;
            if ((c % 2 == 0) && (r % 2 == 0)) {
                b = Bitmap.createBitmap(
                        BitmapFactory.decodeByteArray(imgSup, 0, imgSup.length), 0, 0,
                        TILE_PIXEL_DIM / 2, TILE_PIXEL_DIM / 2);
            } else if ((c % 2 == 1) && (r % 2 == 0)) {
                b = Bitmap.createBitmap(
                        BitmapFactory.decodeByteArray(imgSup, 0, imgSup.length),
                        TILE_PIXEL_DIM / 2, 0, TILE_PIXEL_DIM / 2, TILE_PIXEL_DIM / 2);
            } else if ((c % 2 == 0) && (r % 2 == 1)) {
                b = Bitmap.createBitmap(
                        BitmapFactory.decodeByteArray(imgSup, 0, imgSup.length), 0,
                        TILE_PIXEL_DIM / 2, TILE_PIXEL_DIM / 2, TILE_PIXEL_DIM / 2);
            } else {
                b = Bitmap.createBitmap(
                        BitmapFactory.decodeByteArray(imgSup, 0, imgSup.length),
                        TILE_PIXEL_DIM / 2, TILE_PIXEL_DIM / 2, TILE_PIXEL_DIM / 2,
                        TILE_PIXEL_DIM / 2);
            }

            // Remplir le tampon
            b.compress(Bitmap.CompressFormat.JPEG, 100, buffer);
            try { // Sauvegarder l'image dans le cache disque
                b.compress(Bitmap.CompressFormat.JPEG, 100, new FileOutputStream(
                        cacheFile));
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                // +-------------------------------+
                // | Libérer le verrou de la tuile |
                // +-------------------------------+
                if (locks.get(baseName) != null) {
                    locks.remove(baseName);
                }
                tLock.unlock();
                return null;
            }

            // +-------------------------------+
            // | Libérer le verrou de la tuile |
            // +-------------------------------+
            if (locks.get(baseName) != null) {
                locks.remove(baseName);
            }
            tLock.unlock();
            // Renvoyer le tableau d'octets
            return buffer.toByteArray();
        }
    }

    /**
     * Récupérer une liste de suggestion d'adresses auprès de l'IGN grâce à une
     * requête OpenLS.
     *
     * @param address
     * @param maxReponses
     * @return
     */
    public static GeoLocation[] geolocate(String address, int maxReponses, String cleIGN) {
        URL url;
        HttpURLConnection urlConnection;
        OutputStream output;
        GeoLocation loc[] = new GeoLocation[maxReponses];
        Document dom;

        String content = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + "<XLS\n"
                + "xmlns:xls=\"http://www.opengis.net/xls\"\n"
                + "xmlns:gml=\"http://www.opengis.net/gml\"\n"
                + "xmlns=\"http://www.opengis.net/xls\"\n"
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "version=\"1.2\"\n"
                + "xsi:schemaLocation=\"http://www.opengis.net/xls "
                + "http://schemas.opengis.net/ols/1.2/olsAll.xsd\">\n"
                + "<RequestHeader/>\n" + "<Request requestID=\"1\" version=\"1.2\" "
                + "methodName=\"LocationUtilityService\" " + "maximumResponses=\""
                + maxReponses
                + "\">\n"
                + "<GeocodeRequest returnFreeForm=\"false\">\n"
                + // "<Address countryCode=\"PositionOfInterest\">\n"+
                "<Address countryCode=\"StreetAddress\">\n" + "<freeFormAddress>"
                + address + "</freeFormAddress>\n" + "</Address>\n"
                + "</GeocodeRequest>\n" + "</Request>\n" + "</XLS>\n";
        try {
            // Envoyer la requête
            url = new URL("http://gpp3-wxs.ign.fr/" + cleIGN + "/geoportail/ols");
            urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setDoOutput(true); // pour poster
            urlConnection.setDoInput(true); // pour lire
            urlConnection.setUseCaches(false);
            urlConnection.setRequestProperty("Referer", "http://localhost/IGN/");
            urlConnection.setRequestMethod("POST");
            urlConnection.setRequestProperty("Content-Type", "text/xml");
            urlConnection.connect();
            output = urlConnection.getOutputStream();
            output.write(content.getBytes());
            output.close();

            // Analyser la réponse
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db;
            Element document, adresse, gmlpos;
            NodeList nl, place, streetAddress, building, street, postalCode;
            NamedNodeMap place2;
            String codePostal = null, ville = null, rue = null, numeroRue = null;
            // Récupérer le modèle du document
            db = dbf.newDocumentBuilder();
            dom = db.parse(urlConnection.getInputStream());

            // Extraire les informations pertinentes
            document = dom.getDocumentElement();
            nl = document.getElementsByTagName("GeocodedAddress");

            for (int i = 0; i < nl.getLength(); i++) { // Uniquement la première
                // réponse
                adresse = (Element) nl.item(i);
                gmlpos = (Element) adresse.getElementsByTagName("gml:pos").item(0);
                String[] geo = gmlpos.getTextContent().split(" ");
                loc[i] = new GeoLocation(Double.parseDouble(geo[1]), // longitude
                        Double.parseDouble(geo[0])); // latitude

                // Compléments d'information
                place = adresse.getElementsByTagName("Place");
                for (int j = 0, n = place.getLength(); j < n; j++) { // éléments Place
                    place2 = place.item(j).getAttributes();
                    for (int k = 0, n2 = place2.getLength(); k < n2; k++) { // attributs
                        // de Place

                        if (place2.item(k).getNodeValue().equalsIgnoreCase("Bbox")) { // Bounding
                            // Box
                            String bbox = place.item(j).getTextContent();
                            String[] geobox = bbox.split(";");
                            loc[i].setBoundingBox(Double.parseDouble(geobox[0]),// longmin
                                    Double.parseDouble(geobox[2]), // longmax
                                    Double.parseDouble(geobox[1]), // latmin
                                    Double.parseDouble(geobox[3])); // latmax
                        } else if (place2.item(k).getNodeValue()
                                .equalsIgnoreCase("Commune")) {
                            ville = place.item(j).getTextContent();
                            if (ville.isEmpty()) {
                                ville = null;
                            }
                        }

                    } // Boucle sur attributs de Place
                } // Boucle sur éléments Place

                streetAddress = adresse.getElementsByTagName("StreetAddress");
                if (streetAddress != null) {
                    // Numéro de rue
                    building = ((Element) streetAddress.item(0))
                            .getElementsByTagName("Building");
                    if (building != null && building.getLength() >= 1) {
                        numeroRue = building.item(0).getAttributes().item(0)
                                .getTextContent();
                        if (numeroRue.isEmpty()) {
                            numeroRue = null;
                        }
                    }

                    // Rue
                    street = ((Element) streetAddress.item(0))
                            .getElementsByTagName("Street");
                    if (street != null && street.getLength() >= 1) {
                        rue = street.item(0).getTextContent();
                        if (rue.isEmpty()) {
                            rue = null;
                        }
                    }

                }
                // Code postal
                postalCode = adresse.getElementsByTagName("PostalCode");
                if (postalCode != null && postalCode.getLength() >= 1) {
                    codePostal = postalCode.item(0).getTextContent();
                    if (codePostal.isEmpty()) {
                        codePostal = null;
                    }
                }
                // Ajouter une éventuelle adresse
                loc[i].setAdresse(((numeroRue != null) ? numeroRue + " " : "")
                        + ((rue != null) ? rue + ", " : "")
                        + ((codePostal != null) ? codePostal + " " : "")
                        + ((ville != null) ? ville : ""));
            } // Boucle sur les adresses candidates

        } catch (MalformedURLException e) {

        } catch (java.io.IOException e2) {

        } catch (ParserConfigurationException e3) {

        } catch (SAXException e4) {

        }
        return loc;
    }

}
