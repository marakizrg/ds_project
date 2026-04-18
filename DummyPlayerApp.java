import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Scanner;

// εφαρμογή παίκτη — συνδέεται στον Master για αναζήτηση παιχνιδιών και ποντάρισμα
public class DummyPlayerApp {
    private static final String MASTER_IP   = "localhost";
    private static final int    MASTER_PORT = 9000;

    private static List<Game> searchResults    = null;  // αποτελέσματα της τελευταίας αναζήτησης
    private static boolean    searchDone       = false; // γίνεται true όταν το background thread τελειώσει
    private static boolean    searchInProgress = false; // true ενώ τρέχει η αναζήτηση στο παρασκήνιο
    private static final Object searchLock = new Object(); // monitor για wait/notifyAll μεταξύ main thread και search thread

    public static void main(String[] args) throws InterruptedException {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Καλωσήρθατε στο Online Gaming (Dummy App) ---");

        System.out.print("Δώστε το Player ID σας: ");
        String playerId = scanner.nextLine().trim();

        String lastPlayedGame = null; // κρατάω το τελευταίο παιχνίδι που έπαιξε — για να μπορεί να το αξιολογήσει

        while (true) {
            System.out.println("\n========= ΚΥΡΙΟ ΜΕΝΟΥ =========");
            System.out.println("1. Αναζήτηση παιχνιδιών με φίλτρα");
            System.out.println("2. Εμφάνιση αποτελεσμάτων αναζήτησης");
            System.out.println("3. Ποντάρισμα σε παιχνίδι");
            System.out.println("4. Αξιολόγηση τελευταίου παιχνιδιού");
            System.out.println("5. Έξοδος");

            // εμφανίζω στο menu αν η αναζήτηση τρέχει ακόμα ή αν έχουν έρθει αποτελέσματα
            synchronized (searchLock) {
                if (searchInProgress && !searchDone) {
                    System.out.println("[Αναζήτηση σε εξέλιξη στο παρασκήνιο...]");
                } else if (searchDone) {
                    int count = (searchResults != null) ? searchResults.size() : 0;
                    System.out.println("[Τελευταία αναζήτηση: " + count + " αποτέλεσμα(τα) έτοιμα]");
                }
            }

            System.out.print("Επιλογή: ");
            String line = scanner.nextLine().trim();
            int choice;
            try {
                choice = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("Μη έγκυρη είσοδος.");
                continue;
            }

            switch (choice) {
                case 1:
                    // μαζεύω φίλτρα και ξεκινάω αναζήτηση στο παρασκήνιο — ο χρήστης μπορεί να συνεχίσει αμέσως
                    SearchFilters filters = collectFilters(scanner, playerId);
                    launchAsyncSearch(filters);
                    break;

                case 2:
                    // αν η αναζήτηση τρέχει ακόμα → wait() μέχρι να τελειώσει, αλλιώς εμφανίζει αμέσως
                    viewSearchResults();
                    break;

                case 3:
                    String played = playGame(scanner, playerId);
                    if (played != null) lastPlayedGame = played;
                    break;

                case 4:
                    if (lastPlayedGame != null) {
                        rateGame(scanner, lastPlayedGame);
                    } else {
                        System.out.println("Δεν έχετε παίξει κανένα παιχνίδι ακόμα.");
                    }
                    break;

                case 5:
                    System.out.println("Αντίο!");
                    return;

                default:
                    System.out.println("Μη έγκυρη επιλογή. Επιλέξτε 1-5.");
            }
        }
    }

    // συλλέγει φίλτρα από τον χρήστη — γίνεται στο main thread πριν ξεκινήσει η ασύγχρονη αναζήτηση
    private static SearchFilters collectFilters(Scanner scanner, String playerId) {
        System.out.println("\n--- Ορισμός Φίλτρων Αναζήτησης ---");

        int stars = 0;
        while (true) {
            System.out.print("Ελάχιστα αστέρια (1-5, ή 0 για όλα): ");
            try {
                stars = Integer.parseInt(scanner.nextLine().trim());
                if (stars >= 0 && stars <= 5) break;
            } catch (NumberFormatException e) {}
            System.out.println("Μη έγκυρο! Δώστε αριθμό 0-5.");
        }

        String risk = "";
        while (true) {
            System.out.print("Επίπεδο ρίσκου (low / medium / high / any): ");
            risk = scanner.nextLine().trim().toLowerCase();
            if (risk.equals("low") || risk.equals("medium") || risk.equals("high") || risk.equals("any")) break;
            System.out.println("Μη έγκυρο! Χρησιμοποιήστε: low, medium, high ή any.");
        }
        if (risk.equals("any")) risk = ""; // κενό = χωρίς φίλτρο ρίσκου

        String betCategory = "";
        while (true) {
            System.out.print("Κατηγορία πονταρίσματος ($  /  $$  /  $$$  / any): ");
            betCategory = scanner.nextLine().trim();
            if (betCategory.equals("$") || betCategory.equals("$$") || betCategory.equals("$$$")
                    || betCategory.equalsIgnoreCase("any")) break;
            System.out.println("Μη έγκυρο! Χρησιμοποιήστε: $, $$, $$$ ή any.");
        }
        if (betCategory.equalsIgnoreCase("any")) betCategory = "";

        return new SearchFilters(stars, risk, betCategory, playerId);
    }

    // ξεκινάει την αναζήτηση σε background thread — ο main thread επιστρέφει ΑΜΕΣΩΣ στο menu
    private static void launchAsyncSearch(SearchFilters filters) {
        synchronized (searchLock) {
            if (searchInProgress && !searchDone) {
                System.out.println("Μια αναζήτηση τρέχει ήδη στο παρασκήνιο. Παρακαλώ περιμένετε.");
                return;
            }
            searchInProgress = true;
            searchDone       = false;
            searchResults    = null;
        }

        Thread searchThread = new Thread(() -> {
            List<Game> results = doSearch(filters); // μπλοκάρει μέχρι να έρθει απάντηση από Master
            synchronized (searchLock) {
                searchResults    = results;
                searchDone       = true;
                searchInProgress = false;
                searchLock.notifyAll(); // ξυπνάει τον main thread αν περιμένει στην επιλογή 2
            }
            System.out.println("\n[Αναζήτηση ολοκληρώθηκε! Επιλέξτε '2' για εμφάνιση αποτελεσμάτων.]");
        });
        searchThread.setDaemon(true); // αν κλείσει η εφαρμογή, αυτό το thread κλείνει αυτόματα
        searchThread.start();

        System.out.println("Η αναζήτηση υποβλήθηκε στο παρασκήνιο. Μπορείτε να συνεχίσετε.");
    }

    // εμφανίζει τα αποτελέσματα — αν δεν έχει τελειώσει η αναζήτηση, κάνει wait() μέχρι να ειδοποιηθεί
    private static void viewSearchResults() throws InterruptedException {
        synchronized (searchLock) {
            if (!searchInProgress && !searchDone) {
                System.out.println("Δεν έχει υποβληθεί αναζήτηση. Επιλέξτε πρώτα την επιλογή 1.");
                return;
            }
            if (!searchDone) {
                System.out.println("Αναμονή για αποτελέσματα αναζήτησης...");
                while (!searchDone) {
                    searchLock.wait(); // αποδεσμεύει το lock και κοιμαται μέχρι notifyAll() από το search thread
                }
            }
        }

        List<Game> results;
        synchronized (searchLock) { results = searchResults; }

        if (results == null || results.isEmpty()) {
            System.out.println("Δεν βρέθηκαν παιχνίδια με αυτά τα φίλτρα.");
            return;
        }

        System.out.println("\n--- Αποτελέσματα Αναζήτησης ---");
        for (int i = 0; i < results.size(); i++) {
            Game g = results.get(i);
            System.out.printf("  %d. %-20s | Αστέρια: %d | Ρίσκο: %-6s | MinBet: %.2f | Jackpot: %.0fx%n",
                i, g.gameName, g.stars, g.riskLevel, g.minBet, g.jackpot);
        }
    }

    // εμφανίζει τα διαθέσιμα παιχνίδια, ζητάει επιλογή και ποντάρισμα, και στέλνει PlayRequest στον Master
    private static String playGame(Scanner scanner, String playerId) throws InterruptedException {
        List<Game> games;
        synchronized (searchLock) {
            if (!searchDone || searchResults == null || searchResults.isEmpty()) {
                System.out.println("Δεν υπάρχουν αποτελέσματα αναζήτησης. Κάντε πρώτα αναζήτηση (1) και εμφάνιση (2).");
                return null;
            }
            games = searchResults;
        }

        System.out.println("\n--- Διαθέσιμα Παιχνίδια ---");
        for (int i = 0; i < games.size(); i++) {
            Game g = games.get(i);
            System.out.printf("  %d. %-20s | Αστέρια: %d | Ρίσκο: %-6s | MinBet: %.2f | Jackpot: %.0fx%n",
                i, g.gameName, g.stars, g.riskLevel, g.minBet, g.jackpot);
        }

        int gameIdx = -1;
        while (true) {
            System.out.print("Επιλέξτε αριθμό παιχνιδιού: ");
            try {
                gameIdx = Integer.parseInt(scanner.nextLine().trim());
                if (gameIdx >= 0 && gameIdx < games.size()) break;
            } catch (NumberFormatException e) {}
            System.out.println("Μη έγκυρος αριθμός.");
        }

        Game selected = games.get(gameIdx);

        double bet = 0;
        while (true) {
            System.out.print("Δώστε ποσό πονταρίσματος (ελάχιστο " + selected.minBet + "): ");
            try {
                bet = Double.parseDouble(scanner.nextLine().trim());
                if (bet >= selected.minBet) break;
                System.out.println("Πολύ χαμηλό. Ελάχιστο: " + selected.minBet);
            } catch (NumberFormatException e) {
                System.out.println("Λάθος ποσό.");
            }
        }

        // στέλνω PlayRequest στον Master — αυτός βρίσκει τον σωστό Worker με hashing
        PlayRequest playReq = new PlayRequest(selected.gameName, bet, playerId);
        try (Socket playSocket          = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream playOut = new ObjectOutputStream(playSocket.getOutputStream());
             ObjectInputStream  playIn  = new ObjectInputStream(playSocket.getInputStream())) {

            playOut.writeObject(playReq);
            playOut.flush();

            double result = playIn.readDouble(); // πόσα κέρδισα
            if (result > bet) {
                System.out.println("ΣΥΓΧΑΡΗΤΗΡΙΑ! Κερδίσατε: " + result + " tokens!");
            } else if (result > 0) {
                System.out.println("Πήρατε πίσω: " + result + " tokens.");
            } else {
                System.out.println("Χάσατε. Καλύτερη τύχη την επόμενη φορά!");
            }
        } catch (Exception e) {
            System.err.println("Σφάλμα κατά το ποντάρισμα: " + e.getMessage());
            return null;
        }

        return selected.gameName; // επιστρέφω το όνομα για να μπορεί ο χρήστης να το αξιολογήσει αργότερα
    }

    // στέλνει αξιολόγηση αστεριών για το τελευταίο παιχνίδι που έπαιξε ο χρήστης
    private static void rateGame(Scanner scanner, String gameName) {
        int ratingStars = 0;
        while (true) {
            System.out.print("Δώστε αστέρια (1-5): ");
            try {
                ratingStars = Integer.parseInt(scanner.nextLine().trim());
                if (ratingStars >= 1 && ratingStars <= 5) break;
            } catch (NumberFormatException e) {}
            System.out.println("Λάθος. Δώστε αριθμό 1-5.");
        }

        try (Socket rateSocket          = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream rateOut = new ObjectOutputStream(rateSocket.getOutputStream());
             ObjectInputStream  rateIn  = new ObjectInputStream(rateSocket.getInputStream())) {

            rateOut.writeObject("RATE_GAME:" + gameName + ":" + ratingStars);
            rateOut.flush();

            boolean success = rateIn.readBoolean();
            if (success) {
                System.out.println("Ευχαριστούμε που αξιολογήσατε το \"" + gameName + "\" με " + ratingStars + " αστέρια!");
            } else {
                System.out.println("Δεν ήταν δυνατή η αποστολή αξιολόγησης.");
            }
        } catch (Exception e) {
            System.err.println("Σφάλμα αξιολόγησης: " + e.getMessage());
        }
    }

    // εκτελείται μέσα στο background thread — κάνει την πραγματική TCP επικοινωνία με τον Master
    // μπλοκάρει εδώ μέχρι να έρθει απάντηση, αλλά ο main thread δεν το ξέρει και δεν περιμένει
    @SuppressWarnings("unchecked")
    private static List<Game> doSearch(SearchFilters filters) {
        try (Socket socket          = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject(filters);
            out.flush();

            Object response = in.readObject(); // περιμένω το αποτέλεσμα MapReduce από τον Master
            if (response instanceof List) {
                return (List<Game>) response;
            }
        } catch (Exception e) {
            System.err.println("[Search thread] Σφάλμα επικοινωνίας: " + e.getMessage());
        }
        return null;
    }
}
