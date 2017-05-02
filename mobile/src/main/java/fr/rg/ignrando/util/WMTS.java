package fr.rg.ignrando.util;

/**
 * Created by grosbois on 11/04/15.
 */
public class WMTS {

    // Rayon de la Terre (en m)
    private static final double RAYON_TERRE = 6378137;
    // Ordonnée de la latitude minimale dans la projection Mercator
    private final static int LATMIN_MERCARTOR = (int) (RAYON_TERRE * Math.PI);
    // Abscisse de la longitude minimale dans la projection Mercator
    private final static int LONGMIN_MERCARTOR = -LATMIN_MERCARTOR;

    /**
     * Calcule l'abscisse correspondant à la longitude dans le système de
     * coordonnées WMTS.
     * <p/>
     * L'origine correspond à la longitude la plus à l'ouest, l'axe est orienté
     * de l'ouest vers l'est.
     *
     * @param longitude en degrés
     * @return Abscisse en mètres
     */
    public static double longToWmtsX(double longitude) {
        double longRad = longitude * Math.PI / 180;
        double mX = RAYON_TERRE * longRad; // Mercator
        return mX - LONGMIN_MERCARTOR;
    }

    /**
     * Calcule l'ordonnée correspondant à la latitude dans le système de
     * coordonnées WMTS.
     * <p/>
     * L'origine correspond à la latitude la plus au nord, l'axe est orienté du
     * nord vers le sud.
     *
     * @param latitude en degrés
     * @return Ordonnée en mètres
     */
    public static double latToWmtsY(double latitude) {
        double latRad = latitude * Math.PI / 180;
        double mY = RAYON_TERRE * Math.log(Math.tan(latRad / 2 + Math.PI / 4)); // Mercator
        return LATMIN_MERCARTOR - mY;
    }

    /**
     * Distance couverte par une tuile (en mètres).
     *
     * @param zoom Niveau de zoom correspondant
     */
    public static double getTileDim(int zoom) {
        return (2 * Math.PI * RAYON_TERRE / (1 << zoom));
    }

    /**
     * Calcule l'indice de colonne de la tuile contenant la longitude pour le
     * niveau de zoom spécifié.
     *
     * @param longitude
     * @param zoom
     * @return
     */
    public static int longToTileCol(double longitude, int zoom) {
        return (int) (longToWmtsX(longitude) / getTileDim(zoom));
    }

    /**
     * Calcule l'indice de colonne de la tuile contenant la longitude pour le
     * niveau de zoom spécifié.
     *
     * @param latitude
     * @param zoom
     * @return
     */
    public static int latToTileRow(double latitude, int zoom) {
        return (int) (latToWmtsY(latitude) / getTileDim(zoom));
    }


}
