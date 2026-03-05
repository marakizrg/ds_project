import java.io.*;
import java.net.*;
import java.util.*;

public class Master {
    private static final int PORT = 5000;
    // Λίστα με τους διαθέσιμους Workers
    private List<WorkerInfo> workers = new ArrayList<>();

    public Master() {
        // Προσθήκη των Workers που θα χρησιμοποιήσει το σύστημα
        workers.add(new WorkerInfo("127.0.0.1", 6001));
        workers.add(new WorkerInfo("127.0.0.1", 6002));
    }

    public static void main(String[] args) {
        new Master().startServer();
    }

    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Master Server is running on port " + PORT + "...");
            
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private class ClientHandler implements Runnable {
        private Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
                 ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream())) {

                Object request = in.readObject();

                // 1. MANAGER: Προσθήκη νέου παιχνιδιού (Hashing)
                if (request instanceof Game) {
                    Game newGame = (Game) request;
                    int workerIndex = Math.abs(newGame.gameName.hashCode()) % workers.size();
                    forwardGameToWorker(newGame, workers.get(workerIndex));
                    System.out.println("Game '" + newGame.gameName + "' assigned to Worker on port " + workers.get(workerIndex).port);
                } 
                
                // 2. PLAYER: Αναζήτηση παιχνιδιών (MapReduce)
                else if (request instanceof SearchFilters) {
                    List<Game> results = performMapReduceSearch((SearchFilters) request);
                    out.writeObject(results);
                    out.flush();
                }

                // 3. PLAYER: Ποντάρισμα (Forwarding)
                else if (request instanceof PlayRequest) {
                    PlayRequest playReq = (PlayRequest) request;
                    int workerIndex = Math.abs(playReq.gameName.hashCode()) % workers.size();
                    double winAmount = forwardPlayToWorker(playReq, workers.get(workerIndex));
                    out.writeDouble(winAmount);
                    out.flush();
                }

                // 4. MANAGER: Προβολή στατιστικών (MapReduce Aggregation)
                else if (request instanceof String && request.equals("VIEW_PROFITS")) {
                    Map<String, Double> stats = getGlobalStatistics();
                    out.writeObject(stats);
                    out.flush();
                }

            } catch (Exception e) {
                System.err.println("Error handling client: " + e.getMessage());
            }
        }
    }

    // --- MAPREDUCE: Αναζήτηση Παιχνιδιών ---
    private List<Game> performMapReduceSearch(SearchFilters filters) {
        List<List<Game>> intermediateResults = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
                    out.writeObject(filters);
                    intermediateResults.add((List<Game>) in.readObject());
                } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            threads.add(t);
        }

        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { e.printStackTrace(); }
        }

        List<Game> finalResults = new ArrayList<>();
        for (List<Game> subList : intermediateResults) {
            finalResults.addAll(subList);
        }
        return finalResults;
    }

    // --- MAPREDUCE: Συγκέντρωση Στατιστικών Κέρδους ---
    private Map<String, Double> getGlobalStatistics() {
        Map<String, Double> globalProfits = new HashMap<>();
        List<Map<String, Double>> intermediateResults = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();

        // MAP Phase
        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
                    out.writeObject("GET_STATS");
                    intermediateResults.add((Map<String, Double>) in.readObject());
                } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            threads.add(t);
        }

        for (Thread t : threads) {
            try { t.join(); } catch (InterruptedException e) { e.printStackTrace(); }
        }

        // REDUCE Phase: Aggregation
        for (Map<String, Double> workerMap : intermediateResults) {
            for (Map.Entry<String, Double> entry : workerMap.entrySet()) {
                globalProfits.put(entry.getKey(), globalProfits.getOrDefault(entry.getKey(), 0.0) + entry.getValue());
            }
        }
        return globalProfits;
    }

    private void forwardGameToWorker(Game game, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
            out.writeObject(game);
            out.flush();
        }
    }

    private double forwardPlayToWorker(PlayRequest req, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
            out.writeObject(req);
            out.flush();
            return in.readDouble();
        }
    }
}