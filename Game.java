import java.io.Serializable;

// αναπαριστά ένα παιχνίδι — χρειάζεται Serializable για να το στέλνουμε μέσω socket
public class Game implements Serializable {
    private static final long serialVersionUID = 1L;

    String gameName;
    String providerName;
    int stars;
    int noOfVotes;
    String gameLogoPath;
    double minBet;
    double maxBet;
    String riskLevel;
    String secretKey;

    String betCategory; // βγαίνει αυτόματα από το minBet, δεν το βάζω στο JSON
    double jackpot;     // εξαρτάται από το riskLevel

    public Game(String name, String provider, int stars, int noOfVotes, String gameLogoPath,
                double minBet, double maxBet, String risk, String secretKey) {
        this.gameName     = name;
        this.providerName = provider;
        this.stars        = stars;
        this.noOfVotes    = noOfVotes;
        this.gameLogoPath = gameLogoPath;
        this.minBet       = minBet;
        this.maxBet       = maxBet;
        this.riskLevel    = risk;
        this.secretKey    = secretKey;
        calculateAutomaticFields();
    }

    // υπολογίζει betCategory και jackpot αυτόματα — δεν χρειάζεται να τα έχω στο JSON
    private void calculateAutomaticFields() {
        if (minBet >= 5)      betCategory = "$$$";
        else if (minBet >= 1) betCategory = "$$";
        else                  betCategory = "$";

        if (riskLevel.equalsIgnoreCase("low"))         jackpot = 10;
        else if (riskLevel.equalsIgnoreCase("medium")) jackpot = 20;
        else                                           jackpot = 40;
    }
}
