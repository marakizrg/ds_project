import java.io.Serializable;

// αυτό στέλνει ο παίκτης στον Master όταν θέλει να παίξει κανει extend και αυτη την Serializable για να στελνει με socket
public class PlayRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    public String gameName;
    public double betAmount;
    public String playerId;

    public PlayRequest(String gameName, double betAmount, String playerId) {
        this.gameName  = gameName;
        this.betAmount = betAmount;
        this.playerId  = playerId;
    }
}
