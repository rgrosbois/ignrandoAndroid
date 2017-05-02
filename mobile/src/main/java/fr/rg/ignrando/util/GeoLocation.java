package fr.rg.ignrando.util;


import android.location.Location;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Pour conserver une position de géolocalisation avec une éventuelle adresse et
 * bounding box.
 *
 * La classe est parcelable de manière à ce que les instances puissent être
 * placées dans un Bundle.
 */
public class GeoLocation implements Parcelable {

    // latitude du lieu
    public double latitude;
    // longitude du lieu
    public double longitude;
    // adresse du lieu (si disponible)
    public String address;
    // Zone de géolocalisation contenant le lieu
    public BoundingBox bb;
    // Altitude fournie par le GPS
    public float gpsElevation;
    // Altitude fournie par le baromètre
    public float barElevation = -1;
    // Altitude fournie par un modèle de terrain (-1 signifie que l'altitude n'a
    // jamais été calculée)
    public float modelElevation = -1;
    // Altitude utilisée pour l'affichage
    public float dispElevation;
    // TimeStamp en secondes
    public long timeStampS;
    // Distance parcourue
    public int length;
    // Vitesse instantanée
    public float speed;
    // Fournisseur
    public String provider;
    // Précision
    public float accuracy;

    // /////////// Méthode de Parcelable ///////////////////
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeDouble(latitude);
        parcel.writeDouble(longitude);
        parcel.writeString(address);
        if (bb != null) {
            parcel.writeDouble(bb.latMin);
            parcel.writeDouble(bb.latMax);
            parcel.writeDouble(bb.longMin);
            parcel.writeDouble(bb.longMax);
        } else {
            parcel.writeDouble(0);
            parcel.writeDouble(0);
            parcel.writeDouble(0);
            parcel.writeDouble(0);
        }
        parcel.writeFloat(gpsElevation);
        parcel.writeFloat(barElevation);
        parcel.writeFloat(modelElevation);
        parcel.writeLong(timeStampS);
        parcel.writeInt(length);
        parcel.writeFloat(speed);
        parcel.writeString(provider);
        parcel.writeFloat(accuracy);
    }

    public static final Parcelable.Creator<GeoLocation> CREATOR = new Parcelable.Creator<GeoLocation>() {
        public GeoLocation createFromParcel(Parcel in) {
            return new GeoLocation(in);
        }

        public GeoLocation[] newArray(int size) {
            return new GeoLocation[size];
        }
    };

    public GeoLocation(Parcel parcel) {
        latitude = parcel.readDouble();
        longitude = parcel.readDouble();
        address = parcel.readString();
        bb = new BoundingBox();
        bb.latMin = parcel.readDouble();
        bb.latMax = parcel.readDouble();
        bb.longMin = parcel.readDouble();
        bb.longMax = parcel.readDouble();
        gpsElevation = parcel.readFloat();
        barElevation = parcel.readFloat();
        modelElevation = parcel.readFloat();
        timeStampS = parcel.readLong();
        length = parcel.readInt();
        speed = parcel.readFloat();
        provider = parcel.readString();
        accuracy = parcel.readFloat();
    }

    // /////////////////////////////////////////

    public GeoLocation(Location loc) {
        latitude = loc.getLatitude();
        longitude = loc.getLongitude();
        bb = new BoundingBox();
        gpsElevation = (int) loc.getAltitude();
        timeStampS = (long) (loc.getTime() / 1000); // conversion en secondes
        length = 0;
        speed = 0;
        provider = loc.getProvider();
        accuracy = loc.getAccuracy();
    }

    public GeoLocation() {
        bb = new BoundingBox();
    }

    public GeoLocation copy() {
        GeoLocation loc = new GeoLocation();
        loc.latitude = latitude;
        loc.longitude = longitude;
        loc.gpsElevation = gpsElevation;
        loc.barElevation = barElevation;
        loc.modelElevation = modelElevation;
        loc.timeStampS = timeStampS;
        loc.length = length;
        loc.speed = speed;
        loc.bb.latMin = bb.latMin;
        loc.bb.latMax = bb.latMax;
        loc.bb.longMin = bb.longMin;
        loc.bb.longMax = bb.longMax;
        loc.provider = provider;
        loc.accuracy = accuracy;
        return loc;
    }

    /**
     * Construction à partir d'une longitude et latitude. La bounding box est
     * alors de dimension nulle et centrée sur cette même position.
     *
     * @param longitude
     * @param latitude
     */
    public GeoLocation(double longitude, double latitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.bb = new BoundingBox();
        this.bb.latMin = latitude;
        this.bb.latMax = latitude;
        this.bb.longMin = longitude;
        this.bb.longMax = longitude;
    }

    public GeoLocation(double longitude, double latitude, double longMin,
                       double longMax, double latMin, double latMax) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.bb = new BoundingBox();
        this.bb.latMin = latMin;
        this.bb.latMax = latMax;
        this.bb.longMin = longMin;
        this.bb.longMax = longMax;
    }

    /**
     * Associer une adresse à la géolocalisation.
     *
     * @param adresse
     */
    public void setAdresse(String adresse) {
        this.address = adresse;
    }

    /**
     * Définir une bounding box pour cette géolocalisation.
     *
     * @param longmin
     * @param longmax
     * @param latmin
     * @param latmax
     */
    public void setBoundingBox(double longmin, double longmax, double latmin,
                               double latmax) {
        bb.longMin = longmin;
        bb.longMax = longmax;
        bb.latMin = latmin;
        bb.latMax = latmax;
    }

    @Override
    public String toString() {
        return ((address == null) ? "" : address) + " " + latitude + ","
                + longitude + ", alt=" + gpsElevation + " bar:" + barElevation
                + " modèle:" + modelElevation + ", dist=" + length + ", time="
                + timeStampS + ", vit=" + speed + ", acc=" + accuracy + ", prov="
                + provider;
    }

}
