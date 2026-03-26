import java.io.*;
import java.net.*;
import java.util.*;

public class Master {
    private static final int PORT = 9000; //η θυρα που ακουει ο master για τα αιτηματα απο manager & players
    
    private List<WorkerInfo> workers = new ArrayList<>(); //λιστα με διαθεσιμους workers

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Master master = new Master();
        if (args.length == 0) {
            System.out.println("Usage: java Master <worker_port1> <worker_port2> ...");
            return;
        }
        for (String port : args) {
            master.workers.add(new WorkerInfo("127.0.0.1", Integer.parseInt(port)));
        }
        master.startServer();
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
        public ClientHandler(Socket socket) { this.socket = socket; }

        @Override
        public void run() {
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                Object request = in.readObject();

                if (request instanceof Game) {
                    Game newGame = (Game) request;
                    int workerIndex = Math.abs(newGame.gameName.hashCode()) % workers.size(); 
                    forwardGameToWorker(newGame, workers.get(workerIndex));
                    System.out.println("Game " + newGame.gameName + " added to worker " + workers.get(workerIndex).port);
                } 
                else if (request instanceof SearchFilters) {
                    System.out.println("Starting MapReduce Search...");
                    List<Game> finalResults = performMapReduceSearch((SearchFilters) request);
                    out.writeObject(finalResults);
                    out.flush();
                }
                else if (request instanceof PlayRequest) {
                    PlayRequest playReq = (PlayRequest) request;
                    System.out.println("Processing play for: " + playReq.gameName);
                    // Hashing για να βρούμε τον σωστό worker που έχει το παιχνίδι
                    int workerIndex = Math.abs(playReq.gameName.hashCode()) % workers.size(); 
                    double winAmount = forwardPlayToWorker(playReq, workers.get(workerIndex));

                    out.writeDouble(winAmount); 
                    out.flush();
                }
                else if (request instanceof String && request.equals("VIEW_PROFITS")) {
                    Map<String, Double> stats = getGlobalStatistics();
                    out.writeObject(stats);
                    out.flush();
                }

            } catch (Exception e) {
                System.err.println("Master Error: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }

    private List<Game> performMapReduceSearch(SearchFilters filters) {
        List<Game> finalResults = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
                    out.writeObject(filters); 
                    out.flush();
                    List<Game> results = (List<Game>) in.readObject();
                    finalResults.addAll(results);
                } catch (Exception e) {
                    System.err.println("Worker " + worker.port + " error during search.");
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) { try { t.join(); } catch (InterruptedException e) {} }
        return finalResults;
    }

    private Map<String, Double> getGlobalStatistics() {
        Map<String, Double> globalProfits = new HashMap<>();
        List<Thread> threads = new ArrayList<>();
        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
                    out.writeObject("GET_STATS");
                    out.flush();
                    Map<String, Double> workerMap = (Map<String, Double>) in.readObject();
                    synchronized (globalProfits) {
                        for (Map.Entry<String, Double> entry : workerMap.entrySet()) {
                            globalProfits.put(entry.getKey(), globalProfits.getOrDefault(entry.getKey(), 0.0) + entry.getValue());
                        }
                    }
                } catch (Exception e) { e.printStackTrace(); }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) { try { t.join(); } catch (InterruptedException e) {} }
        return globalProfits;
    }

    private void forwardGameToWorker(Game game, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())){
            out.writeObject(game);
            out.flush();
        }
    }

    private double forwardPlayToWorker(PlayRequest req, WorkerInfo worker) {
    Socket s = null;
    try {
        s = new Socket();
        // Timeout 2 δευτερόλεπτα για να μην περιμένει για πάντα αν ο worker είναι offline
        s.connect(new InetSocketAddress(worker.ip, worker.port), 2000);
        
        ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
        out.flush(); // Στέλνουμε τον header αμέσως
        
        ObjectInputStream in = new ObjectInputStream(s.getInputStream());

        // Στέλνουμε το String και μετά το Object
        out.writeObject("PLAY");
        out.flush();
        
        out.writeObject(req);
        out.flush();
        
        // Διάβασμα αποτελέσματος
        double result = in.readDouble();
        return result;

    } catch (Exception e) {
        System.err.println("Master Error: Could not connect to Worker at " + worker.port + " -> " + e.getMessage());
        return 0.0;
    } finally {
        try { if (s != null) s.close(); } catch (IOException e) {}
    }
}
}