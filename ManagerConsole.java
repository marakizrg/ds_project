import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.Scanner;

// εφαρμογή διαχειριστή — συνδέεται στον Master για να διαχειριστεί τα παιχνίδια και να δει κέρδη
public class ManagerConsole {
    private static final String MASTER_IP   = "localhost";
    private static final int    MASTER_PORT = 9000;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println("--- Λειτουργία Manager ---");

        while (true) {
            System.out.println("\nΕπιλέξτε Ενέργεια:");
            System.out.println("1. Προσθήκη Παιχνιδιού (από JSON αρχείο)");
            System.out.println("2. Κέρδη ανά Παιχνίδι");
            System.out.println("3. Κέρδη/Ζημιές Συγκεκριμένου Παίκτη");
            System.out.println("4. Διαγραφή Παιχνιδιού");
            System.out.println("5. Αλλαγή Επιπέδου Ρίσκου Παιχνιδιού");
            System.out.println("6. Έξοδος");
            System.out.print("\nΕπιλογή: ");

            if (!scanner.hasNextInt()) {
                System.out.println("Λάθος. Διαλέξτε αριθμό απο 1 εως 6.");
                scanner.next();
                continue;
            }

            int choice = scanner.nextInt();
            scanner.nextLine();

            if (choice < 1 || choice > 6) {
                System.out.println("Αριθμός εκτός ορίων. Διαλέξτε αριθμό απο 1 εως 6.");
                continue;
            }

            if      (choice == 1) addNewGame(scanner);
            else if (choice == 2) viewProfits();
            else if (choice == 3) viewProfitsByPlayer(scanner);
            else if (choice == 4) removeGame(scanner);
            else if (choice == 5) modifyGameRisk(scanner);
            else {
                System.out.println("Έξοδος από Λειτουργία Manager");
                break;
            }
        }
    }

    // εξάγει τιμή String από raw JSON χωρίς εξωτερική βιβλιοθήκη — ψάχνει το κλειδί και επιστρέφει την τιμή σε εισαγωγικά
    private static String extractJsonString(String json, String key) {
        String jsonLower = json.toLowerCase();
        String search    = "\"" + key.toLowerCase() + "\"";
        int keyIdx       = jsonLower.indexOf(search);
        if (keyIdx < 0) return "";
        int colonIdx  = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return "";
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return "";
        int quoteEnd   = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return "";
        return json.substring(quoteStart + 1, quoteEnd);
    }

    // εξάγει αριθμητική τιμή (double) από raw JSON — ίδια λογική με το extractJsonString
    private static double extractJsonNumber(String json, String key) {
        String jsonLower = json.toLowerCase();
        String search    = "\"" + key.toLowerCase() + "\"";
        int keyIdx       = jsonLower.indexOf(search);
        if (keyIdx < 0) return 0.0;
        int colonIdx = json.indexOf(':', keyIdx + search.length());
        if (colonIdx < 0) return 0.0;
        int start = colonIdx + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t'
                || json.charAt(start) == '\n' || json.charAt(start) == '\r')) {
            start++;
        }
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end))
                || json.charAt(end) == '.' || json.charAt(end) == '-')) {
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    // διαβάζει JSON αρχείο, φτιάχνει Game αντικείμενο και το στέλνει στον Master
    private static void addNewGame(Scanner scanner) {
        System.out.print("\nΔώστε path του JSON αρχείου του παιχνιδιού: ");
        String filePath = scanner.nextLine().trim();

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            System.err.println("Σφάλμα ανάγνωσης αρχείου: " + e.getMessage());
            return;
        }

        // εξαγωγή πεδίων από το JSON — δεν χρησιμοποιώ εξωτερική βιβλιοθήκη
        String json      = sb.toString();
        String name      = extractJsonString(json, "GameName");
        String provider  = extractJsonString(json, "ProviderName");
        String logoPath  = extractJsonString(json, "GameLogo");
        String risk      = extractJsonString(json, "RiskLevel");
        String secretKey = extractJsonString(json, "HashKey");
        int    stars     = (int) extractJsonNumber(json, "Stars");
        int    noOfVotes = (int) extractJsonNumber(json, "NoOfVotes");
        double minBet    = extractJsonNumber(json, "MinBet");
        double maxBet    = extractJsonNumber(json, "MaxBet");

        if (name.isEmpty() || provider.isEmpty() || risk.isEmpty() || secretKey.isEmpty()) {
            System.err.println("Λάθος: Λείπουν υποχρεωτικά πεδία (GameName, ProviderName, RiskLevel, HashKey).");
            return;
        }
        if (logoPath.isEmpty()) logoPath = "N/A";
        if (stars < 1 || stars > 5)  { System.err.println("Λάθος: Τα stars πρέπει να είναι 1-5."); return; }
        if (minBet <= 0)              { System.err.println("Λάθος: Το minBet πρέπει να είναι θετικό."); return; }
        if (maxBet < minBet)          { System.err.println("Λάθος: maxBet < minBet."); return; }

        // betCategory και jackpot υπολογίζονται αυτόματα στον constructor
        Game newGame = new Game(name, provider, stars, noOfVotes, logoPath, minBet, maxBet, risk, secretKey);

        try (Socket socket           = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out  = new ObjectOutputStream(socket.getOutputStream())) {
            out.writeObject(newGame);
            out.flush();
            System.out.println("Το παιχνίδι \"" + name + "\" προστέθηκε! [betCategory=" + newGame.betCategory + ", jackpot=" + newGame.jackpot + "]");
        } catch (Exception e) {
            System.err.println("Σφάλμα προσθήκης: " + e.getMessage());
        }
    }

    // στέλνει εντολή REMOVE_GAME στον Master — ο Master βρίσκει τον σωστό Worker με hashing
    private static void removeGame(Scanner scanner) {
        try (Socket socket          = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

            System.out.print("\nΌνομα παιχνιδιού προς διαγραφή: ");
            String name = scanner.nextLine().trim();

            out.writeObject("REMOVE_GAME:" + name);
            out.flush();
            System.out.println("Στάλθηκε αίτημα διαγραφής για: " + name);

        } catch (Exception e) {
            System.err.println("Σφάλμα διαγραφής: " + e.getMessage());
        }
    }

    // στέλνει εντολή MODIFY_RISK στον Master — ο Worker αλλάζει riskLevel και jackpot στη μνήμη
    private static void modifyGameRisk(Scanner scanner) {
        try (Socket socket          = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream())) {

            System.out.print("\nΌνομα παιχνιδιού: ");
            String name = scanner.nextLine().trim();

            String newRisk = "";
            while (true) {
                System.out.print("Νέο επίπεδο ρίσκου (low / medium / high): ");
                newRisk = scanner.nextLine().toLowerCase().trim();
                if (newRisk.equals("low") || newRisk.equals("medium") || newRisk.equals("high")) break;
                System.out.println("Λάθος. Επιλέξτε: low, medium, high");
            }

            out.writeObject("MODIFY_RISK:" + name + ":" + newRisk);
            out.flush();

            boolean success = in.readBoolean(); // true αν ο Worker βρήκε το παιχνίδι
            if (success) {
                System.out.println("Το ρίσκο του \"" + name + "\" ενημερώθηκε σε: " + newRisk);
            } else {
                System.out.println("Σφάλμα: Το παιχνίδι \"" + name + "\" δεν βρέθηκε.");
            }

        } catch (Exception e) {
            System.err.println("Σφάλμα αλλαγής ρίσκου: " + e.getMessage());
        }
    }

    // ζητάει κέρδη ανά παιχνίδι από τον Master (MapReduce) και τα εμφανίζει
    @SuppressWarnings("unchecked")
    private static void viewProfits() {
        try (Socket socket          = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VIEW_PROFITS");
            out.flush();

            Object response = in.readObject();
            if (response instanceof Map) {
                Map<String, Double> stats = (Map<String, Double>) response;
                System.out.println("\n--- Κέρδη ανά Παιχνίδι ---");
                if (stats.isEmpty()) {
                    System.out.println("Δεν υπάρχουν δεδομένα ακόμα.");
                } else {
                    double total = 0;
                    for (Map.Entry<String, Double> entry : stats.entrySet()) {
                        System.out.printf("Παιχνίδι: %-20s | Κέρδος: %10.2f tokens%n",
                                          entry.getKey(), entry.getValue());
                        total += entry.getValue();
                    }
                    System.out.println("--------------------------------------------------");
                    System.out.printf("ΣΥΝΟΛΙΚΟ ΚΕΡΔΟΣ : %.2f tokens%n", total);
                }
            }
        } catch (Exception e) {
            System.err.println("Σφάλμα λήψης στατιστικών: " + e.getMessage());
        }
    }

    // ζητάει τη ζημιά ενός συγκεκριμένου παίκτη από τον Master (MapReduce) και την εμφανίζει
    @SuppressWarnings("unchecked")
    private static void viewProfitsByPlayer(Scanner scanner) {
        System.out.print("\nΔώστε Player ID: ");
        String playerId = scanner.nextLine().trim();

        try (Socket socket          = new Socket(MASTER_IP, MASTER_PORT);
             ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
             ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream())) {

            out.writeObject("VIEW_PROFITS_PLAYER:" + playerId);
            out.flush();

            Object response = in.readObject();
            if (response instanceof Map) {
                Map<String, Double> stats = (Map<String, Double>) response;
                System.out.println("\n--- Καθαρό Αποτέλεσμα Παίκτη: " + playerId + " ---");
                if (stats.isEmpty()) {
                    System.out.println("Δεν βρέθηκαν δεδομένα για τον παίκτη: " + playerId);
                } else {
                    for (Map.Entry<String, Double> entry : stats.entrySet()) {
                        // θετική τιμή = η πλατφόρμα κέρδισε = ο παίκτης έχασε
                        System.out.printf("Παίκτης: %-15s | Καθαρό Αποτέλεσμα: %10.2f tokens%n",
                                          entry.getKey(), entry.getValue());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Σφάλμα λήψης στατιστικών παίκτη: " + e.getMessage());
        }
    }
}
