import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;

public class Worker {
    private Map<String, Game> games = new HashMap<>();
    private Map<String, Double> gameProfits = new HashMap<>();
    private final Queue<Integer> randomBuffer = new LinkedList<>();
    private final int BUFFER_SIZE = 50;
    
    private static final String SRG_IP = "localhost";
    private static final int SRG_PORT = 4321;

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6001;
        new Worker().startWorker(port);
    }

    public void startWorker(int port) {
        new Thread(new RandomProducer()).start();
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Worker started on port " + port + "...");
            while (true) {
                Socket masterSocket = serverSocket.accept();
                new Thread(new MasterHandler(masterSocket)).start();
            }
        } catch (IOException e) { e.printStackTrace(); }
    }

    private class MasterHandler implements Runnable {
        private Socket socket;
        public MasterHandler(Socket socket) { this.socket = socket; }

       @Override
public void run() {
    try {
        socket.setSoTimeout(5000); // Αν δεν λάβει τίποτα σε 5 δευτερόλεπτα, κλείνει
        ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        
        ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
        Object request = in.readObject();

        if (request instanceof String && request.equals("PLAY")) {
            Object next = in.readObject();
            if (next instanceof PlayRequest) {
                PlayRequest pr = (PlayRequest) next;
                System.out.println("[Worker] Playing game: " + pr.gameName);
                double win = calculatePlay(pr);
                out.writeDouble(win);
                out.flush();
            }
        } 
        // ... οι υπόλοιπες περιπτώσεις (Game, SearchFilters κλπ)
        else if (request instanceof Game) {
            Game g = (Game) request;
            games.put(g.gameName, g);
            System.out.println("[Worker] Added: " + g.gameName);
        }
        // ... (πρόσθεσε και το SearchFilters και το GET_STATS όπως πριν)

    } catch (Exception e) {
        System.err.println("[Worker] Communication error: " + e.getMessage());
    } finally {
        try { socket.close(); } catch (IOException e) {}
    }
}
    }

    private double calculatePlay(PlayRequest pr) {
        Game g = games.get(pr.gameName);
        if (g == null) return 0.0;

        int num;
        synchronized (randomBuffer) {
            while (randomBuffer.isEmpty()) {
                try { randomBuffer.wait(); } catch (InterruptedException e) {}
            }
            num = randomBuffer.poll();
            randomBuffer.notifyAll();
        }

        double winAmount;
        if (num % 100 == 0) {
            winAmount = pr.betAmount * g.jackpot;
        } else {
            int index = num % 10;
            double[] multipliers = getMultipliers(g.riskLevel);
            winAmount = pr.betAmount * multipliers[index];
        }

        synchronized(gameProfits) {
            double profit = pr.betAmount - winAmount;
            gameProfits.put(g.gameName, gameProfits.getOrDefault(g.gameName, 0.0) + profit);
        }
        return winAmount;
    }

    private class RandomProducer implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    synchronized (randomBuffer) {
                        while (randomBuffer.size() >= BUFFER_SIZE) randomBuffer.wait();
                    }
                    try (Socket srgSocket = new Socket(SRG_IP, SRG_PORT);
                         ObjectOutputStream out = new ObjectOutputStream(srgSocket.getOutputStream())) {
                        
                        out.flush();
                        try (ObjectInputStream in = new ObjectInputStream(srgSocket.getInputStream())) {
                            out.writeUTF("secret123");
                            out.flush();
                            int num = in.readInt();
                            byte[] receivedHash = (byte[]) in.readObject();
                            if (verifyHash(num, "secret123", receivedHash)) {
                                synchronized (randomBuffer) {
                                    randomBuffer.add(num);
                                    randomBuffer.notifyAll();
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
        if (risk.equalsIgnoreCase("low")) return new double[]{0.0, 0.0, 0.0, 0.1, 0.5, 1.0, 1.1, 1.3, 2.0, 2.5};
        if (risk.equalsIgnoreCase("medium")) return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.5, 2.5, 3.5};
        return new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 6.5};
    }
}