import java.io.Serializable;

public class Game implements Serializable {
    private static final long serialVersionUID = 1L;
    String gameName;
    String providerName;
    int stars;
    int noOfVotes;
    String gameLogoPath;
    double minBet;
    double maxBet;
    String riskLevel; // low, medium, high [cite: 51]
    String secretKey; // Το secret S για το hash [cite: 52, 82]
    
    // Αυτά υπολογίζονται αυτόματα [cite: 59]
    String betCategory; // $, $$, $$$
    double jackpot;

    public Game(String name, String provider, int stars, int noOfVotes, String gameLogoPath,
                double minBet, double maxBet, String risk, String secretKey) {
        this.gameName = name;
        this.providerName = provider;
        this.stars = stars;
        this.noOfVotes = noOfVotes;
        this.gameLogoPath = gameLogoPath;
        this.minBet = minBet;
        this.maxBet = maxBet;
        this.riskLevel = risk;
        this.secretKey = secretKey;
        calculateAutomaticFields();
    }

    private void calculateAutomaticFields() {
        // Υπολογισμός κατηγορίας πονταρίσματος [cite: 56, 57, 58]
        if (minBet >= 5) betCategory = "$$$";
        else if (minBet >= 1) betCategory = "$$";
        else betCategory = "$";

        // Υπολογισμός Jackpot βάσει ρίσκου [cite: 76, 77]
        if (riskLevel.equalsIgnoreCase("low")) jackpot = 10;
        else if (riskLevel.equalsIgnoreCase("medium")) jackpot = 20;
        else jackpot = 40;
    }
}