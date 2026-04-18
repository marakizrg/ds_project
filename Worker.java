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

    private final Map<String, Queue<Integer>> gameBuffers = new HashMap<>(); // secretKey -> buffer τυχαίων αριθμών για κάθε παιχνίδι
    private final int BUFFER_SIZE = 50; // μέγιστος αριθμός τυχαίων αριθμων στον buffer πριν σταματήσει ο Producer

    private static final String SRG_IP   = "localhost";
    private static final int    SRG_PORT = 4321;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6001;
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

    // επιστρέφει τον buffer τυχαίων για ένα παιχνίδι αν δεν υπάρχει ακόμα, τον δημιουργεί και ξεκινάει τον Producer
    private synchronized Queue<Integer> getOrCreateBuffer(String secretKey) {
        if (!gameBuffers.containsKey(secretKey)) {
            Queue<Integer> buffer = new LinkedList<>();
            gameBuffers.put(secretKey, buffer);
            new Thread(new RandomProducer(secretKey, buffer)).start();
        }
        return gameBuffers.get(secretKey);
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
                    // αποθηκεύει νέο παιχνίδι στη μνήμη και ξεκινάει αμέσως τον Producer για αυτό
                    Game g = (Game) request;
                    synchronized (games) { games.put(g.gameName, g); }
                    removedGames.remove(g.gameName);
                    getOrCreateBuffer(g.secretKey);
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

        Queue<Integer> buffer = getOrCreateBuffer(g.secretKey);
        int num;

        // περιμένει μέχρι ο Producer να βάλει κάτι στον buffer
        synchronized (buffer) {
            while (buffer.isEmpty()) {
                try { buffer.wait(); } catch (InterruptedException e) {}
            }
            num = buffer.poll();
            buffer.notifyAll(); // λέω στον Producer ότι έχει ελεύθερη θέση
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

    // τρέχει ασύγχρονα στο background και γεμίζει τον buffer με τυχαίους αριθμούς από τον SRGServer
    // κάνει wait() αν ο buffer είναι γεμάτος
    private class RandomProducer implements Runnable {
        private final String secret;         // secretKey του παιχνιδιού το οποιο το στέλνω στον SRG για το hash
        private final Queue<Integer> buffer; // κοινός buffer με τον Consumer (calculatePlay)

        RandomProducer(String secret, Queue<Integer> buffer) {
            this.secret = secret;
            this.buffer = buffer;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    // περιμένω αν ο buffer είναι ήδη γεμάτος
                    synchronized (buffer) {
                        while (buffer.size() >= BUFFER_SIZE) buffer.wait();
                    }

                    // ζητάω νέο τυχαίο αριθμό από τον SRG Server
                    try (Socket srgSocket = new Socket(SRG_IP, SRG_PORT);
                         ObjectOutputStream out = new ObjectOutputStream(srgSocket.getOutputStream())) {
                        out.flush();
                        try (ObjectInputStream in = new ObjectInputStream(srgSocket.getInputStream())) {
                            out.writeUTF(secret);
                            out.flush();

                            int num             = in.readInt();          // ο τυχαίος αριθμός
                            byte[] receivedHash = (byte[]) in.readObject(); // το SHA-256 hash για επαλήθευση

                            // αν το hash δεν ταιριάζει, πετάω τον αριθμό
                            if (verifyHash(num, secret, receivedHash)) {
                                synchronized (buffer) {
                                    buffer.add(num);
                                    buffer.notifyAll(); // ειδοποιώ τον Consumer ότι υπάρχει αριθμός
                                }
                            }
                        }
                    }
                    Thread.sleep(100); // μικρή παύση για να μην πλημμυρίσω τον SRG με αιτήματα

                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {} // αν ο SRG πέσει, ξαναπροσπαθώ σε 1 δευτερόλεπτο
                }
            }
        }
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
