import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public class Worker {
    private Map<String, Game> games = new HashMap<>();
    private Set<String> removedGames = new HashSet<>();
    private Map<String, Double> gameProfits = new HashMap<>();
    private Map<String, Double> playerProfits = new HashMap<>();

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
        @SuppressWarnings("unchecked")
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
                    getOrCreateBuffer(g.secretKey);
                    System.out.println("[Worker] Added: " + g.gameName);

                } else if (request instanceof SearchFilters) {
                    // MAP: φιλτράρει τα τοπικά παιχνίδια και επιστρέφει αποτελέσματα
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
                    System.out.println("[Worker] Search returned " + matches.size() + " games.");

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
                            success = true;
                            System.out.println("[Worker] Modified risk of " + gameName + " -> " + newRisk);
                        }
                    }
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("RATE_GAME:")) {
                    String[] parts = ((String) request).split(":", 3);
                    String gameName = parts[1];
                    int newStars = Integer.parseInt(parts[2]);
                    boolean success = false;
                    synchronized (games) {
                        Game g = games.get(gameName);
                        if (g != null && !removedGames.contains(gameName)) {
                            g.stars = Math.round((float)(g.stars * g.noOfVotes + newStars) / (g.noOfVotes + 1));
                            g.noOfVotes++;
                            success = true;
                            System.out.println("[Worker] Rated " + gameName + ": stars=" + g.stars + ", votes=" + g.noOfVotes);
                        }
                    }
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && ((String) request).equals("GET_STATS")) {
                    // MAP για κέρδη/ζημιές ανά παιχνίδι
                    Map<String, Double> copy;
                    synchronized (gameProfits) { copy = new HashMap<>(gameProfits); }
                    out.writeObject(copy);
                    out.flush();

                } else if (request instanceof String && ((String) request).equals("GET_PROVIDER_STATS")) {
                    // MAP για κέρδη/ζημιές ανά πάροχο
                    Map<String, Double> providerMap = new HashMap<>();
                    synchronized (games) {
                        synchronized (gameProfits) {
                            for (Map.Entry<String, Double> entry : gameProfits.entrySet()) {
                                Game g = games.get(entry.getKey());
                                String provider = (g != null) ? g.providerName : "Unknown";
                                providerMap.put(provider,
                                    providerMap.getOrDefault(provider, 0.0) + entry.getValue());
                            }
                        }
                    }
                    out.writeObject(providerMap);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("GET_PLAYER_STATS:")) {
                    // MAP για κέρδη/ζημιές συγκεκριμένου παίκτη
                    String playerId = ((String) request).substring("GET_PLAYER_STATS:".length());
                    Map<String, Double> playerMap = new HashMap<>();
                    synchronized (playerProfits) {
                        if (playerProfits.containsKey(playerId)) {
                            playerMap.put(playerId, playerProfits.get(playerId));
                        }
                    }
                    out.writeObject(playerMap);
                    out.flush();

                } else if (request instanceof String && ((String) request).equals("GET_ALL_PLAYER_STATS")) {
                    // MAP για κέρδη/ζημιές όλων των παικτών
                    Map<String, Double> copy;
                    synchronized (playerProfits) { copy = new HashMap<>(playerProfits); }
                    out.writeObject(copy);
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

        if (pr.playerId != null && !pr.playerId.isEmpty()) {
            synchronized (playerProfits) {
                playerProfits.put(pr.playerId,
                    playerProfits.getOrDefault(pr.playerId, 0.0) + (pr.betAmount - winAmount));
            }
        }

        return winAmount;
    }

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
