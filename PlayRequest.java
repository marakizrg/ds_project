import java.io.Serializable;

public class PlayRequest implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public String gameName;  // Το όνομα του παιχνιδιού που επέλεξε [cite: 73]
    public double betAmount; // Το ποσό που ποντάρει [cite: 73]

    public PlayRequest(String gameName, double betAmount) {
        this.gameName = gameName;
        this.betAmount = betAmount;
    }
}