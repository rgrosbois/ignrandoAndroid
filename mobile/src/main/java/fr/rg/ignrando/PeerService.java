package fr.rg.ignrando;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;

import static fr.rg.ignrando.MainActivity.DEBUG_TAG;

/**
 * Created by grosbois on 29/04/17.
 */

public class PeerService extends Service {

    static final String MULTICAST_ADDRESS = "224.0.71.75";
    static final int MULTICAST_PORT = 7175;
    static final long HELLO_INTERVAL = 2000;
    static final long DEAD_INTERVAL = 10000;

    Thread beaconThread = null;
    Thread tcpServer = null;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d("RESEAU", "onCreate PeerService");

        beaconThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // Configuration de la socket et préparation du paquet à émettre
                MulticastSocket s = null;
                String msg = "Hello";
                byte[] buf = msg.getBytes();
                DatagramPacket pkt = null;
                try {
                    pkt = new DatagramPacket(buf, buf.length,
                            InetAddress.getByName(MULTICAST_ADDRESS), MULTICAST_PORT);
                    s = new MulticastSocket();
                    s.setLoopbackMode(true); // Ne pas envoyer sur lo

                    // Trouver l'interface réseau WiFi
                    NetworkInterface wifiNetworkInterface = findWifiNetworkInterface();
                    if (wifiNetworkInterface != null)
                        s.setNetworkInterface(wifiNetworkInterface);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }

                // Émission périodique
                while (true) {
                    try {
                        s.send(pkt);

                        Thread.sleep(HELLO_INTERVAL);
                    } catch (InterruptedException | IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        beaconThread.start();

        // Serveur TCP
        tcpServer = new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket listenSocket = null;
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(PeerService.this);
                try {
                    listenSocket = new ServerSocket(MULTICAST_PORT);
                    boolean continuer = true;
                    Socket commSocket;
                    while (continuer) {
                        // Attendre le prochain client et ouvrir les canaux de
                        // communications
                        commSocket = listenSocket.accept();

                        BufferedReader netIn = new BufferedReader(new InputStreamReader(commSocket.getInputStream()));
                        PrintWriter netOut = new PrintWriter(commSocket.getOutputStream(), true);
                        BufferedOutputStream netOutB = new BufferedOutputStream(commSocket.getOutputStream());

                        String msg = netIn.readLine();
                        while (msg != null && !msg.equals("QUIT")) {
                            switch (msg) {
                                case "INFO":
                                    Log.d(DEBUG_TAG, "Peer get INFO");
                                    netOut.println("Dépôt KML : " + prefs.getString(FileListFragment.CUR_DIR_KEY,
                                            Environment.getExternalStorageDirectory().getPath()));
                                    netOut.println("Clé IGN : " + prefs.getString(IGNTileProvider.IGNKEY_KEY, ""));
                                    netOut.println("Géolocalisation : "
                                            + prefs.getFloat(MainActivity.LATITUDE_PREF_KEY, 0) + ", "
                                            + prefs.getFloat(MainActivity.LONGITUDE_PREF_KEY, 0));
                                    break;
                                case "FILES":
                                    Log.d(DEBUG_TAG, "Peer get FILES list");
                                    File curDir = new File(prefs.getString(FileListFragment.CUR_DIR_KEY,
                                            Environment.getExternalStorageDirectory().getPath()));
                                    for (File file : curDir.listFiles()) {
                                        netOut.println(file.getName());
                                    }
                                    break;
                                case "GET FILE":
                                    msg = netIn.readLine();
                                    Log.d(DEBUG_TAG, "Peer get FILE " + msg);
                                    File file = new File(prefs.getString(FileListFragment.CUR_DIR_KEY,
                                            Environment.getExternalStorageDirectory().getPath()),
                                            msg);
                                    if (file.exists()) {
                                        // Envoyer la longueur du fichier
                                        int len = (int) file.length();
                                        netOut.println("" + len);
                                        // Envoyer le fichier par tranche de 1024 octets
                                        BufferedInputStream inFile = new BufferedInputStream(new FileInputStream(file));
                                        byte[] buf = new byte[len];
                                        inFile.read(buf, 0, len);
                                        netOutB.write(buf, 0, len);
                                        netOutB.flush();
                                        inFile.close();
                                        Log.d(DEBUG_TAG, "Fichier transféré");
                                    } else {
                                        netOut.println("" + 0);
                                    }
                                    break;
                                default:
                            }
                            netOut.println("END"); // Terminer la réponse

                            msg = netIn.readLine();
                        }

                        // Fermer les communications
                        netIn.close();
                        netOut.close();
                    }
                    listenSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }

            }
        });
        tcpServer.start();
    }

    @Override
    public void onDestroy() {
        beaconThread.interrupt();
        tcpServer.interrupt();

        Log.d(DEBUG_TAG, "onDestroy PeerService");

        super.onDestroy();
    }

    /**
     * Finds Network Interface of Wifi Ethernet.
     *
     * @return
     */
    public static NetworkInterface findWifiNetworkInterface() {

        Enumeration<NetworkInterface> enumeration = null;

        try {
            enumeration = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            e.printStackTrace();
        }

        NetworkInterface wlan0 = null;

        while (enumeration.hasMoreElements()) {

            wlan0 = enumeration.nextElement();

            if (wlan0.getName().equals("wlan0")) {
                return wlan0;
            }
        }

        return null;
    }
}
