import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public class Worker {
    // Αποθήκευση παιχνιδιών στη μνήμη [cite: 66, 109]
    private Map<String, Game> games = new HashMap<>();
    
    // Καταγραφή κερδών/ζημιών ανά παιχνίδι για το MapReduce [cite: 10, 88, 90]
    private Map<String, Double> gameProfits = new HashMap<>();
    
    // Buffer για τυχαίους αριθμούς (Producer-Consumer) 
    private final Queue<Integer> randomBuffer = new LinkedList<>();
    private final int BUFFER_SIZE = 50;
    
    private static final String SRG_IP = "localhost";
    private static final int SRG_PORT = 4321;

    public static void main(String[] args) {
        // Ορισμός θύρας (π.χ. 6001) [cite: 101]
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6001;
        new Worker().startWorker(port);
    }

    public void startWorker(int port) {
        // Έναρξη του Producer thread 
        new Thread(new RandomProducer()).start();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Worker started on port " + port + "...");
            while (true) {
                Socket masterSocket = serverSocket.accept();
                // Πολυνηματική εξυπηρέτηση του Master [cite: 100]
                new Thread(new MasterHandler(masterSocket)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    // --- PRODUCER: Λήψη αριθμών από SRG Server [cite: 78, 79, 80, 99] ---
    private class RandomProducer implements Runnable {
        @Override
        public void run() {
            while (true) {
                synchronized (randomBuffer) {
                    while (randomBuffer.size() >= BUFFER_SIZE) {
                        try { randomBuffer.wait(); } catch (InterruptedException e) {}
                    }
                }

                try (Socket srgSocket = new Socket(SRG_IP, SRG_PORT);
                     ObjectOutputStream out = new ObjectOutputStream(srgSocket.getOutputStream());
                     ObjectInputStream in = new ObjectInputStream(srgSocket.getInputStream())) {
                    
                    out.writeUTF("secret123"); // Το secret S από το json [cite: 82]
                    out.flush();

                    int num = in.readInt();
                    byte[] receivedHash = (byte[]) in.readObject();

                    // Επαλήθευση SHA-256 [cite: 83, 84]
                    if (verifyHash(num, "secret123", receivedHash)) {
                        synchronized (randomBuffer) {
                            randomBuffer.add(num);
                            randomBuffer.notifyAll(); // Ειδοποίηση Consumer 
                        }
                    }
                } catch (Exception e) { 
                    try { Thread.sleep(1000); } catch (InterruptedException ie) {}
                }
            }
        }
    }

    // --- MASTER HANDLER: Εξυπηρέτηση αιτημάτων Master [cite: 100, 107] ---
    private class MasterHandler implements Runnable {
        private Socket socket;
        public MasterHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {
                
                Object request = in.readObject();

                if (request instanceof Game) {
                    Game g = (Game) request;
                    games.put(g.gameName, g);
                } 
                else if (request instanceof SearchFilters) {
                    // Φιλτράρισμα παιχνιδιών (Map Phase) [cite: 15, 22, 71]
                    SearchFilters sf = (SearchFilters) request;
                    List<Game> matches = new ArrayList<>();
                    for (Game g : games.values()) {
                        if (g.stars >= sf.minStars && g.riskLevel.equalsIgnoreCase(sf.riskLevel)) {
                            matches.add(g);
                        }
                    }
                    out.writeObject(matches);
                    out.flush();
                }
                else if (request instanceof PlayRequest) {
                    // Εκτέλεση Πονταρίσματος [cite: 23, 73, 85]
                    PlayRequest pr = (PlayRequest) request;
                    double result = calculatePlay(pr);
                    out.writeDouble(result);
                    out.flush();
                }
                else if (request instanceof String && request.equals("GET_STATS")) {
                    // Επιστροφή στατιστικών για MapReduce [cite: 91, 92]
                    out.writeObject(new HashMap<>(gameProfits));
                    out.flush();
                }
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    // --- CONSUMER Λογική: Υπολογισμός Κέρδους/Ζημιάς [cite: 81, 85, 87] ---
    private double calculatePlay(PlayRequest pr) {
        Game g = games.get(pr.gameName);
        if (g == null) return 0;

        int num;
        synchronized (randomBuffer) {
            while (randomBuffer.isEmpty()) {
                try { randomBuffer.wait(); } catch (InterruptedException e) {} // 
            }
            num = randomBuffer.poll();
            randomBuffer.notifyAll(); // 
        }

        double winAmount;
        // Έλεγχος Jackpot (αριθμός % 100 == 0) [cite: 85, 86]
        if (num % 100 == 0) {
            winAmount = pr.betAmount * g.jackpot;
        } 
        // Τακτικό κέρδος βάσει πίνακα ρίσκου [cite: 87]
        else {
            int index = num % 10;
            double[] multipliers = getMultipliers(g.riskLevel);
            winAmount = pr.betAmount * multipliers[index];
        }

        // Ενημέρωση εσόδων/ζημιών συστήματος [cite: 88, 89]
        synchronized(gameProfits) {
            double profit = pr.betAmount - winAmount;
            gameProfits.put(g.gameName, gameProfits.getOrDefault(g.gameName, 0.0) + profit);
        }

        return winAmount;
    }

    private boolean verifyHash(int num, String secret, byte[] receivedHash) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256"); // [cite: 83, 84]
        byte[] expectedHash = md.digest((num + secret).getBytes("UTF-8"));
        return Arrays.equals(receivedHash, expectedHash);
    }

    private double[] getMultipliers(String risk) {
        // Προκαθορισμένοι πίνακες ρίσκου [cite: 76, 77]
        if (risk.equalsIgnoreCase("low")) return new double[]{0.0, 0.0, 0.0, 0.1, 0.5, 1.0, 1.1, 1.3, 2.0, 2.5};
        if (risk.equalsIgnoreCase("medium")) return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.5, 2.5, 3.5};
        return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 6.5};
    }
}