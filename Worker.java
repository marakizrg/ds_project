import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public class Worker {
    private Map<String, Game> games = new HashMap<>();
    // Παιχνίδια που αφαιρέθηκαν: δεν εμφανίζονται στην αναζήτηση αλλά τα στατιστικά παραμένουν
    private Set<String> removedGames = new HashSet<>();
    private Map<String, Double> gameProfits = new HashMap<>();

    // Ένα buffer τυχαίων αριθμών ανά παιχνίδι (secretKey -> Queue)
    private final Map<String, Queue<Integer>> gameBuffers = new HashMap<>();
    private final int BUFFER_SIZE = 50;

    private static final String SRG_IP = "localhost";
    private static final int SRG_PORT = 4321;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6001;
        new Worker().startWorker(port);
    }

    public void startWorker(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Worker started on port " + port + "...");
            while (true) {
                Socket masterSocket = serverSocket.accept();
                new Thread(new MasterHandler(masterSocket)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // Εξασφαλίζει ότι υπάρχει buffer για το δεδομένο secretKey και ξεκινά τον Producer αν χρειάζεται
    private synchronized Queue<Integer> getOrCreateBuffer(String secretKey) {
        if (!gameBuffers.containsKey(secretKey)) {
            Queue<Integer> buffer = new LinkedList<>();
            gameBuffers.put(secretKey, buffer);
            new Thread(new RandomProducer(secretKey, buffer)).start();
        }
        return gameBuffers.get(secretKey);
    }

    private class MasterHandler implements Runnable {
        private Socket socket;
        public MasterHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(5000);
                ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                out.flush();
                ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                Object request = in.readObject();

                if (request instanceof Game) {
                    Game g = (Game) request;
                    synchronized (games) { games.put(g.gameName, g); }
                    removedGames.remove(g.gameName);
                    // Ξεκίνα producer για το secretKey αυτού του παιχνιδιού
                    getOrCreateBuffer(g.secretKey);
                    System.out.println("[Worker] Added: " + g.gameName);

                } else if (request instanceof String && ((String) request).startsWith("REMOVE_GAME:")) {
                    String gameName = ((String) request).substring("REMOVE_GAME:".length());
                    synchronized (games) { removedGames.add(gameName); }
                    System.out.println("[Worker] Removed: " + gameName);

                } else if (request instanceof String && ((String) request).startsWith("MODIFY_RISK:")) {
                    String[] parts = ((String) request).split(":", 3);
                    String gameName = parts[1];
                    String newRisk  = parts[2];
                    boolean success = false;
                    synchronized (games) {
                        Game g = games.get(gameName);
                        if (g != null && !removedGames.contains(gameName)) {
                            g.riskLevel = newRisk;
                            if (newRisk.equalsIgnoreCase("low")) g.jackpot = 10;
                            else if (newRisk.equalsIgnoreCase("medium")) g.jackpot = 20;
                            else g.jackpot = 40;
                            System.out.println("[Worker] Modified risk of " + gameName + " -> " + newRisk);
                            success = true;
                        }
                    }
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof SearchFilters) {
                    SearchFilters sf = (SearchFilters) request;
                    List<Game> matches = new ArrayList<>();
                    synchronized (games) {
                        for (Game g : games.values()) {
                            if (removedGames.contains(g.gameName)) continue;
                            if (g.stars < sf.minStars) continue;
                            if (!g.riskLevel.equalsIgnoreCase(sf.riskLevel)) continue;
                            if (sf.betCategory != null && !sf.betCategory.isEmpty()
                                    && !g.betCategory.equals(sf.betCategory)) continue;
                            matches.add(g);
                        }
                    }
                    out.writeObject(matches);
                    out.flush();

                } else if (request instanceof String && ((String) request).equals("PLAY")) {
                    Object next = in.readObject();
                    if (next instanceof PlayRequest) {
                        PlayRequest pr = (PlayRequest) next;
                        System.out.println("[Worker] Playing game: " + pr.gameName);
                        double win = calculatePlay(pr);
                        out.writeDouble(win);
                        out.flush();
                    }

                } else if (request instanceof String && ((String) request).equals("GET_STATS")) {
                    synchronized (gameProfits) {
                        out.writeObject(new HashMap<>(gameProfits));
                    }
                    out.flush();
                }

            } catch (Exception e) {
                System.err.println("[Worker] Communication error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }

    private double calculatePlay(PlayRequest pr) {
        Game g;
        synchronized (games) { g = games.get(pr.gameName); }
        if (g == null) return 0.0;

        // Παίρνουμε τυχαίο αριθμό από το buffer του συγκεκριμένου παιχνιδιού
        Queue<Integer> buffer = getOrCreateBuffer(g.secretKey);
        int num;
        synchronized (buffer) {
            while (buffer.isEmpty()) {
                try { buffer.wait(); } catch (InterruptedException e) {}
            }
            num = buffer.poll();
            buffer.notifyAll();
        }

        double winAmount;
        if (num % 100 == 0) {
            winAmount = pr.betAmount * g.jackpot;
        } else {
            int index = num % 10;
            double[] multipliers = getMultipliers(g.riskLevel);
            winAmount = pr.betAmount * multipliers[index];
        }

        synchronized (gameProfits) {
            double profit = pr.betAmount - winAmount;
            gameProfits.put(g.gameName, gameProfits.getOrDefault(g.gameName, 0.0) + profit);
        }
        return winAmount;
    }

    // Producer: γεννά τυχαίους αριθμούς από τον SRG για ένα συγκεκριμένο secretKey
    private class RandomProducer implements Runnable {
        private final String secret;
        private final Queue<Integer> buffer;

        RandomProducer(String secret, Queue<Integer> buffer) {
            this.secret = secret;
            this.buffer = buffer;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    synchronized (buffer) {
                        while (buffer.size() >= BUFFER_SIZE) buffer.wait();
                    }
                    try (Socket srgSocket = new Socket(SRG_IP, SRG_PORT);
                         ObjectOutputStream out = new ObjectOutputStream(srgSocket.getOutputStream())) {
                        out.flush();
                        try (ObjectInputStream in = new ObjectInputStream(srgSocket.getInputStream())) {
                            out.writeUTF(secret);
                            out.flush();
                            int num = in.readInt();
                            byte[] receivedHash = (byte[]) in.readObject();
                            if (verifyHash(num, secret, receivedHash)) {
                                synchronized (buffer) {
                                    buffer.add(num);
                                    buffer.notifyAll();
                                }
                            }
                        }
                    }
                    Thread.sleep(100);
                } catch (Exception e) {
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                }
            }
        }
    }

    private boolean verifyHash(int num, String secret, byte[] receivedHash) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] expectedHash = md.digest((num + secret).getBytes("UTF-8"));
        return Arrays.equals(receivedHash, expectedHash);
    }

    private double[] getMultipliers(String risk) {
        if (risk.equalsIgnoreCase("low"))    return new double[]{0.0, 0.0, 0.0, 0.1, 0.5, 1.0, 1.1, 1.3, 2.0, 2.5};
        if (risk.equalsIgnoreCase("medium")) return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.5, 2.5, 3.5};
        return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 6.5};
    }
}
