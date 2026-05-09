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

    // παράγει PlayRequest από JSON string — για επικοινωνία με Android app
    public static PlayRequest fromJson(String json) {
        String game = extractStr(json, "gameName");
        double bet  = Double.parseDouble(extractNum(json, "betAmount"));
        String pid  = extractStr(json, "playerId");
        return new PlayRequest(game, bet, pid);
    }

    private static String extractStr(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static String extractNum(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\":([-\\d.]+)").matcher(json);
        return m.find() ? m.group(1) : "0";
    }
}
