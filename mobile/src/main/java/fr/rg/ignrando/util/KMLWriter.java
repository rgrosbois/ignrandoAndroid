package fr.rg.ignrando.util;


import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import android.os.Parcelable;
import android.text.format.Time;

import fr.rg.ignrando.MainActivity;


public class KMLWriter {

    // Elements du fichier KML
    public static final String KML_NAMESPACE = "http://earth.google.com/kml/2.1";
    public static final String KML_NAMESPACE2 = "http://www.opengis.net/kml/2.2";
    public static final String KML_TYPE = "kml";
    public static final String KML_DOCUMENT = "Document";
    public static final String KML_FOLDER = "Folder";
    public static final String KML_PLACEMARK = "Placemark";
    public static final String KML_TIMESTAMP = "TimeStamp";
    public static final String KML_WHEN = "when";
    public static final String KML_POINT = "Point";
    public static final String KML_LINESTRING = "LineString";
    public static final String KML_COORDINATES = "coordinates";

    /*
     * Écrire le fichier KML.
     */
    public static void writeKMLFile(ArrayList<Parcelable> locList, FileOutputStream out,
                                    boolean elevFromGPS) {
        if(locList==null || locList.size()==0) return;
        Time time = new Time();
        try {
            // En-tête du fichier KML
            String tmp = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
            tmp += "<"+KML_TYPE+" xmlns=\""+KML_NAMESPACE+"\" "+
                    "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n";
            tmp += "\t<"+KML_DOCUMENT+">\n";
            time.setToNow();
            tmp += "\t\t<name>"+time.format("%d/%m/%Y %H:%M")+"</name>\n";
            tmp += "\t\t<description>"+MainActivity.class.getName()
                    +" by R. Grosbois for Android.</description>\n";
            tmp += "\t\t<Style id=\"track_n\"><IconStyle><Icon><href>http://earth.google.com/images/kml-icons/track-directional/track-none.png</href></Icon></IconStyle></Style>\n";
            tmp += "\t\t<Style id=\"track_h\"><IconStyle><scale>1.2</scale><Icon><href>http://earth.google.com/images/kml-icons/track-directional/track-none.png</href></Icon></IconStyle></Style>\n";
            tmp += "\t\t<StyleMap id=\"track\"><Pair><key>normal</key><styleUrl>#track_n</styleUrl></Pair><Pair><key>highlight</key><styleUrl>#track_h</styleUrl></Pair></StyleMap>\n";
            tmp += "\t\t<Style id=\"lineStyle\"><LineStyle><color>99ffac59</color><width>6</width></LineStyle></Style>\n";

            // En-tête du folder de points
            tmp += "\t\t<"+KML_FOLDER+">\n\t\t\t<name>Points</name>\n";
            out.write(tmp.getBytes());

            // Folder de points
            int len = locList.size();
            GeoLocation loc;
            for(int i=0; i<len; i++) {
                loc = (GeoLocation)locList.get(i);
                time.set(loc.timeStampS*1000); // temps en ms

                // Fichier de points
                tmp = "\n\t\t\t<"+KML_PLACEMARK+">\n"+
                        "\t\t\t\t<"+KML_TIMESTAMP+"><"+KML_WHEN+">"+time.format("%Y-%m-%dT%H:%M:%S+01:00")+
                        "</"+KML_WHEN+"></"+KML_TIMESTAMP+">\n"+
                        "\t\t\t\t<styleUrl>#track</styleUrl>\n"+
                        "\t\t\t\t<Point><coordinates>"+loc.longitude+","+loc.latitude+","+
                        (elevFromGPS?loc.gpsElevation:loc.barElevation)+"</coordinates></Point>\n"+
                        "\t\t\t\t<description><![CDATA[prov="+loc.provider+
                        ", acc="+loc.accuracy+"]]></description>\n"+
                        "\t\t\t</"+KML_PLACEMARK+">\n";
                out.write(tmp.getBytes());
            }
            tmp = "\t\t</Folder>\n";

            // Folder de lineString
            tmp += "\t\t<"+KML_PLACEMARK+">\n\t\t\t<name>Path</name>\n\t\t\t<styleUrl>#lineStyle</styleUrl>\n"+
                    "\t\t\t<LineString>\n\t\t\t\t<tessellate>1</tessellate>\n"+
                    "\t\t\t\t<altitudeMode>clampToGround</altitudeMode>\n\t\t\t\t<coordinates>\n";
            out.write(tmp.getBytes());
            for(int i=0; i<len; i++) {
                loc = (GeoLocation)locList.get(i);
                tmp = ("\t\t\t\t\t"+loc.longitude+","+loc.latitude+","+
                        (elevFromGPS?loc.gpsElevation:loc.barElevation)+
                        "\n");
                out.write(tmp.getBytes());
            }
            tmp = "\t\t\t\t</coordinates>\n\t\t\t</LineString>\n\t\t</"+KML_PLACEMARK+">\n\n";
            out.write(tmp.getBytes());

            // Finaliser le fichier et le fermer
            tmp = "\t</"+KML_DOCUMENT+">\n</"+KML_TYPE+">\n";
            out.write(tmp.getBytes());
            out.close();
        } catch(IOException e) {
            e.printStackTrace();
        }
    }

}
