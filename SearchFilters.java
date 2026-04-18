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
}
