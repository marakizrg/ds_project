import java.io.Serializable;

public class PlayRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    public String gameName;  // Το όνομα του παιχνιδιού που επέλεξε [cite: 73]
    public double betAmount; // Το ποσό που ποντάρει [cite: 73]
    public String playerId;  // Το ID του παίκτη

    public PlayRequest(String gameName, double betAmount, String playerId) {
        this.gameName = gameName;
        this.betAmount = betAmount;
        this.playerId = playerId;
    }
}