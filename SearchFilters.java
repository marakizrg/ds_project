import java.io.Serializable;

// φίλτρα που στέλνει ο παίκτης για αναζητηση
public class SearchFilters implements Serializable {
    private static final long serialVersionUID = 1L;

    public int minStars;      
    public String riskLevel;   
    public String betCategory; 
    public String playerId;

    public SearchFilters(int minStars, String riskLevel, String betCategory, String playerId) {
        this.minStars     = minStars;
        this.riskLevel    = riskLevel;
        this.betCategory  = betCategory;
        this.playerId     = playerId;
    }

    // παράγει SearchFilters από JSON string — για επικοινωνία με Android app
    public static SearchFilters fromJson(String json) {
        int minStars = Integer.parseInt(extractNum(json, "minStars"));
        String risk  = extractStr(json, "riskLevel");
        String bet   = extractStr(json, "betCategory");
        String pid   = extractStr(json, "playerId");
        return new SearchFilters(minStars,
                                 risk.isEmpty()  ? null : risk,
                                 bet.isEmpty()   ? null : bet,
                                 pid);
    }

    private static String extractStr(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static String extractNum(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\"" + key + "\":([-\\d]+)").matcher(json);
        return m.find() ? m.group(1) : "0";
    }
}
