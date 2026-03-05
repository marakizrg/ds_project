import java.io.Serializable;

public class SearchFilters implements Serializable {
    private static final long serialVersionUID = 1L;
    
    public int minStars;        // 1-5 αστέρια [cite: 20]
    public String riskLevel;    // low, medium, high [cite: 18]
    public String betCategory;  // $, $$, $$$ [cite: 17]

    public SearchFilters(int minStars, String riskLevel) {
        this.minStars = minStars;
        this.riskLevel = riskLevel;
    }
}