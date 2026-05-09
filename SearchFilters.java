import java.io.Serializable;
import java.util.UUID;

// φίλτρα που στέλνει ο παίκτης για αναζητηση
public class SearchFilters implements Serializable {
    private static final long serialVersionUID = 1L;

    public int minStars;
    public String riskLevel;
    public String betCategory;
    public String playerId;
    public String requestId; // μοναδικό ID αιτήματος — αποτρέπει data collision σε κατανεμημένο περιβάλλον

    public SearchFilters(int minStars, String riskLevel, String betCategory, String playerId) {
        this.minStars     = minStars;
        this.riskLevel    = riskLevel;
        this.betCategory  = betCategory;
        this.playerId     = playerId;
        this.requestId    = UUID.randomUUID().toString();
    }

    // παράγει SearchFilters από JSON string — για επικοινωνία με Android app
    public static SearchFilters fromJson(String json) {
        int minStars    = Integer.parseInt(extractNum(json, "minStars"));
        String risk     = extractStr(json, "riskLevel");
        String bet      = extractStr(json, "betCategory");
        String pid      = extractStr(json, "playerId");
        String rid      = extractStr(json, "requestId");
        SearchFilters sf = new SearchFilters(minStars,
                                             risk.isEmpty() ? null : risk,
                                             bet.isEmpty()  ? null : bet,
                                             pid);
        // αν το Android app έστειλε requestId, το χρησιμοποιώ αντί για το auto-generated
        if (!rid.isEmpty()) sf.requestId = rid;
        return sf;
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
