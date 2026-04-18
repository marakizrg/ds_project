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
    String riskLevel; // low, medium, high 
    String secretKey; //hash key
    
    // Αυτά υπολογίζονται αυτόματα 
    String betCategory; // $, $$, $$$
    double jackpot;

    public Game(String name, String provider, int stars, int noOfVotes, String gameLogoPath, double minBet, double maxBet, String risk, String secretKey) {
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
        // Υπολογισμός κατηγορίας πονταρίσματος
        if (minBet >= 5) betCategory = "$$$";
        else if (minBet >= 1) betCategory = "$$";
        else betCategory = "$";

        // Υπολογισμός Jackpot βάσει ρίσκου
        //το equalsIgnoreCase χρησιμοποιείται για να συγκρίνει δύο συμβολοσειρές ανεξάρτητα από το αν είναι κεφαλαία ή μικρά γράμματα.
        if (riskLevel.equalsIgnoreCase("low")) jackpot = 10;
        else if (riskLevel.equalsIgnoreCase("medium")) jackpot = 20;
        else jackpot = 40;
    }
}