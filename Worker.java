import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;


public class Worker {
                                                                //key -> value
    private Map<String, Game>   games         = new HashMap<>();  // gameName -> Game
    private Set<String>         removedGames  = new HashSet<>();  // παιχνίδια που "διαγράφηκαν" (όχι από τη μνήμη)
    private Map<String, Double> gameProfits   = new HashMap<>();  // gameName -> συνολικό κέρδος πλατφόρμας
    private Map<String, Double> playerProfits = new HashMap<>();  // playerId -> συνολική καθαρή ζημιά παίκτη

    private static String SRG_IP   = "localhost"; // ορίζεται από args
    private static final int SRG_PORT = 4321;

    public static void main(String[] args) {
        // Χρήση: java Worker <port> <srg_ip>
        if (args.length < 2) {
            System.out.println("Χρήση: java Worker <port> <srg_ip>");
            System.out.println("Παράδειγμα: java Worker 6001 192.168.1.11");
            return;
        }
        int port = Integer.parseInt(args[0]);
        SRG_IP   = args[1];
        new Worker().startWorker(port);
    }

    // ξεκινάει τον Worker server κάθε αίτημα από τον Master το χειρίζεται ξεχωριστό thread
    public void startWorker(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Worker started on port " + port + "...");
            while (true) {
                Socket masterSocket = serverSocket.accept();
                new Thread(new MasterHandler(masterSocket)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // χειρίζεται κάθε αίτημα που έρχεται από τον Master
    private class MasterHandler implements Runnable {
        private Socket socket;
        public MasterHandler(Socket socket) { this.socket = socket; }

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                socket.setSoTimeout(5000); // αν δεν έρθει τίποτα σε 5 δευτερόλεπτα, κλείνουμε για να μην κολλάει το thread
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                Object request = in.readObject();

                if (request instanceof Game) {
                    // αποθηκεύει νέο παιχνίδι στη μνήμη
                    Game g = (Game) request;
                    synchronized (games) { games.put(g.gameName, g); }
                    removedGames.remove(g.gameName);
                    System.out.println("[Worker] Προστέθηκε: " + g.gameName);

                } else if (request instanceof SearchFilters) {
                    // MAP: φιλτράρει τα τοπικά παιχνίδια και επιστρέφει αυτά που ταιριάζουν
                    SearchFilters sf = (SearchFilters) request;
                    List<Game> matches = new ArrayList<>();
                    synchronized (games) {
                        for (Game g : games.values()) {
                            if (removedGames.contains(g.gameName)) continue;
                            if (sf.minStars > 0 && g.stars < sf.minStars) continue;
                            if (sf.riskLevel != null && !sf.riskLevel.isEmpty()
                                    && !g.riskLevel.equalsIgnoreCase(sf.riskLevel)) continue;
                            if (sf.betCategory != null && !sf.betCategory.isEmpty()
                                    && !g.betCategory.equals(sf.betCategory)) continue;
                            matches.add(g);
                        }
                    }
                    out.writeObject(matches);
                    out.flush();
                    System.out.println("[Worker] Αναζήτηση: " + matches.size() + " αποτελέσματα.");

                } else if (request instanceof String && ((String) request).startsWith("REMOVE_GAME:")) {
                    // λογική διαγραφή δεν σβήνω από το HashMap
                    String gameName = ((String) request).substring("REMOVE_GAME:".length());
                    synchronized (games) { removedGames.add(gameName); }
                    System.out.println("[Worker] Αφαιρέθηκε: " + gameName);

                } else if (request instanceof String && ((String) request).startsWith("MODIFY_RISK:")) {
                    // αλλάζει το riskLevel του παιχνιδιού στη μνήμη και ξαναυπολογίζει το jackpot
                    String[] parts   = ((String) request).split(":", 3);
                    String gameName  = parts[1];
                    String newRisk   = parts[2];
                    boolean success  = false;
                    synchronized (games) {
                        Game g = games.get(gameName);
                        if (g != null && !removedGames.contains(gameName)) {
                            g.riskLevel = newRisk;
                            if (newRisk.equalsIgnoreCase("low"))         g.jackpot = 10;
                            else if (newRisk.equalsIgnoreCase("medium")) g.jackpot = 20;
                            else                                         g.jackpot = 40;
                            success = true;
                            System.out.println("[Worker] Άλλαξε risk του " + gameName + " -> " + newRisk);
                        }
                    }
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("RATE_GAME:")) {
                    // ενημερώνει τα αστέρια : (παλιά * ψήφοι + νέα) / (ψήφοι+1)
                    String[] parts  = ((String) request).split(":", 3);
                    String gameName = parts[1];
                    int newStars    = Integer.parseInt(parts[2]);
                    boolean success = false;
                    synchronized (games) {
                        Game g = games.get(gameName);
                        if (g != null && !removedGames.contains(gameName)) {
                            g.stars = Math.round((float)(g.stars * g.noOfVotes + newStars) / (g.noOfVotes + 1));
                            g.noOfVotes++;
                            success = true;
                            System.out.println("[Worker] Αξιολόγηση " + gameName + ": stars=" + g.stars);
                        }
                    }
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && ((String) request).equals("GET_STATS")) {
                    // MAP: επιστρέφει τα κέρδη της πλατφόρμας ανά παιχνίδι
                    Map<String, Double> copy;
                    synchronized (gameProfits) { copy = new HashMap<>(gameProfits); }
                    out.writeObject(copy);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("GET_PLAYER_STATS:")) {
                    // MAP: επιστρέφει τη ζημιά για έναν συγκεκριμένο παίκτη
                    String playerId = ((String) request).substring("GET_PLAYER_STATS:".length());
                    Map<String, Double> playerMap = new HashMap<>();
                    synchronized (playerProfits) {
                        if (playerProfits.containsKey(playerId)) {
                            playerMap.put(playerId, playerProfits.get(playerId));
                        }
                    }
                    out.writeObject(playerMap);
                    out.flush();

                } else if (request instanceof String && ((String) request).equals("PLAY")) {
                    // διαβάζει το PlayRequest και επιστρέφει πόσα κέρδισε ο παίκτης
                    Object next = in.readObject();
                    if (next instanceof PlayRequest) {
                        PlayRequest pr = (PlayRequest) next;
                        System.out.println("[Worker] Ποντάρισμα για: " + pr.gameName);
                        double win = calculatePlay(pr);
                        out.writeDouble(win);
                        out.flush();
                    }
                }

            } catch (Exception e) {
                System.err.println("[Worker] Σφάλμα επικοινωνίας: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }

    // υπολογίζει το αποτέλεσμα ενός ποντάρισματος
    private double calculatePlay(PlayRequest pr) {
        Game g;
        synchronized (games) { g = games.get(pr.gameName); }
        if (g == null) return 0.0;

        // ζητάει έναν αριθμό απευθείας από τον SRG Server — ο buffer βρίσκεται εκεί πλέον
        int num;
        try (Socket srgSocket       = new Socket(SRG_IP, SRG_PORT);
             ObjectOutputStream out = new ObjectOutputStream(srgSocket.getOutputStream());
             ObjectInputStream  in  = new ObjectInputStream(srgSocket.getInputStream())) {
            out.flush();
            out.writeUTF(g.secretKey);
            out.flush();
            num = in.readInt();
            byte[] hash = (byte[]) in.readObject();
            if (!verifyHash(num, g.secretKey, hash)) {
                System.err.println("[Worker] Hash mismatch για " + pr.gameName + " — αποτέλεσμα ακυρώθηκε.");
                return 0.0;
            }
        } catch (Exception e) {
            System.err.println("[Worker] Αδυναμία επικοινωνίας με SRG: " + e.getMessage());
            return 0.0;
        }

        double winAmount;
        if (num % 100 == 0) {
            // jackpot, βγαίνει μόνο αν ο αριθμός είναι πολλαπλάσιο του 100
            winAmount = pr.betAmount * g.jackpot;
        } else {
            // κανονικό αποτέλεσμα o index 0-9 δείχνει ποιον πολλαπλασιαστή παίρνω
            int index = num % 10;
            double[] multipliers = getMultipliers(g.riskLevel);
            winAmount = pr.betAmount * multipliers[index];
        }

        // ενημερώνω το κέρδος της πλατφόρμας ανά παιχνίδι (θετικό = η πλατφόρμα κέρδισε)
        synchronized (gameProfits) {
            double profit = pr.betAmount - winAmount;
            gameProfits.put(g.gameName, gameProfits.getOrDefault(g.gameName, 0.0) + profit);
        }

        // ενημερώνω τη ζημιά του παίκτη (θετικό = ο παίκτης έχασε συνολικά)
        if (pr.playerId != null && !pr.playerId.isEmpty()) {
            synchronized (playerProfits) {
                playerProfits.put(pr.playerId,
                    playerProfits.getOrDefault(pr.playerId, 0.0) + (pr.betAmount - winAmount));
            }
        }

        return winAmount;
    }

    // ελέγχει αν ο SHA-256(αριθμός + secret) == receivedHash
    private boolean verifyHash(int num, String secret, byte[] receivedHash) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] expectedHash = md.digest((num + secret).getBytes("UTF-8"));
        return Arrays.equals(receivedHash, expectedHash);
    }

    // επιστρέφει τους πολλαπλασιαστές κέρδους για το επίπεδο ρίσκου
    private double[] getMultipliers(String risk) {
        if (risk.equalsIgnoreCase("low"))
            return new double[]{0.0, 0.0, 0.0, 0.1, 0.5, 1.0, 1.1, 1.3, 2.0, 2.5};
        if (risk.equalsIgnoreCase("medium"))
            return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.5, 2.5, 3.5};
        return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 6.5}; // high
    }
}
