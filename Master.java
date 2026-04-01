import java.io.*;
import java.net.*;
import java.util.*;

public class Master {
    private static final int PORT = 9000;
    private static final String REDUCER_IP = "localhost";
    private static final int REDUCER_PORT = 8500;

    private List<WorkerInfo> workers = new ArrayList<>();

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
        @SuppressWarnings("unchecked")
        public void run() {
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

                Object request = in.readObject();

                if (request instanceof Game) {
                    Game newGame = (Game) request;
                    int workerIndex = Math.abs(newGame.gameName.hashCode()) % workers.size();
                    forwardGameToWorker(newGame, workers.get(workerIndex));
                    System.out.println("Game " + newGame.gameName + " added to worker " + workers.get(workerIndex).port);

                } else if (request instanceof SearchFilters) {
                    System.out.println("Starting MapReduce Search...");
                    List<Game> results = performMapReduceSearch((SearchFilters) request);
                    out.writeObject(results);
                    out.flush();

                } else if (request instanceof PlayRequest) {
                    PlayRequest playReq = (PlayRequest) request;
                    System.out.println("Processing play for: " + playReq.gameName);
                    int workerIndex = Math.abs(playReq.gameName.hashCode()) % workers.size();
                    double winAmount = forwardPlayToWorker(playReq, workers.get(workerIndex));
                    out.writeDouble(winAmount);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("REMOVE_GAME:")) {
                    String gameName = ((String) request).substring("REMOVE_GAME:".length());
                    int workerIndex = Math.abs(gameName.hashCode()) % workers.size();
                    forwardStringToWorker((String) request, workers.get(workerIndex));
                    System.out.println("Remove request forwarded for: " + gameName);

                } else if (request instanceof String && ((String) request).startsWith("MODIFY_RISK:")) {
                    String[] parts = ((String) request).split(":", 3);
                    String gameName = parts[1];
                    int workerIndex = Math.abs(gameName.hashCode()) % workers.size();
                    boolean success = forwardStringWithResponse((String) request, workers.get(workerIndex));
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("RATE_GAME:")) {
                    String[] parts = ((String) request).split(":", 3);
                    String gameName = parts[1];
                    int workerIndex = Math.abs(gameName.hashCode()) % workers.size();
                    boolean success = forwardStringWithResponse((String) request, workers.get(workerIndex));
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && request.equals("VIEW_PROFITS")) {
                    Map<String, Double> stats = performMapReduceStats("GET_STATS");
                    out.writeObject(stats);
                    out.flush();

                } else if (request instanceof String && request.equals("VIEW_PROFITS_PROVIDER")) {
                    Map<String, Double> stats = performMapReduceStats("GET_PROVIDER_STATS");
                    out.writeObject(stats);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("VIEW_PROFITS_PLAYER:")) {
                    String playerId = ((String) request).substring("VIEW_PROFITS_PLAYER:".length());
                    Map<String, Double> stats = performMapReduceStats("GET_PLAYER_STATS:" + playerId);
                    out.writeObject(stats);
                    out.flush();

                } else if (request instanceof String && request.equals("VIEW_ALL_PLAYERS")) {
                    Map<String, Double> stats = performMapReduceStats("GET_ALL_PLAYER_STATS");
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

    // MAP: συλλέγει αποτελέσματα αναζήτησης από όλους τους Workers παράλληλα
    // REDUCE: στέλνει όλα τα αποτελέσματα στον Reducer για συνδυασμό
    @SuppressWarnings("unchecked")
    private List<Game> performMapReduceSearch(SearchFilters filters) {
        final List<Object> mapResults = new ArrayList<>();

        // MAP phase
        List<Thread> threads = new ArrayList<>();
        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream wOut = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream wIn = new ObjectInputStream(s.getInputStream())) {
                    wOut.writeObject(filters);
                    wOut.flush();
                    Object result = wIn.readObject();
                    synchronized (mapResults) { mapResults.add(result); }
                } catch (Exception e) {
                    System.err.println("Worker " + worker.port + " error during search: " + e.getMessage());
                    synchronized (mapResults) { mapResults.add(new ArrayList<Game>()); }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) { try { t.join(); } catch (InterruptedException e) {} }

        // REDUCE phase
        try (Socket s = new Socket(REDUCER_IP, REDUCER_PORT);
             ObjectOutputStream rOut = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream rIn = new ObjectInputStream(s.getInputStream())) {
            rOut.writeObject("REDUCE_SEARCH");
            rOut.writeObject(mapResults);
            rOut.flush();
            return (List<Game>) rIn.readObject();
        } catch (Exception e) {
            System.err.println("Reducer unavailable, combining locally: " + e.getMessage());
            List<Game> combined = new ArrayList<>();
            for (Object r : mapResults) {
                if (r instanceof List) combined.addAll((List<Game>) r);
            }
            return combined;
        }
    }

    // MAP: συλλέγει στατιστικά από όλους τους Workers παράλληλα
    // REDUCE: στέλνει στον Reducer για άθροιση
    @SuppressWarnings("unchecked")
    private Map<String, Double> performMapReduceStats(String workerCommand) {
        final List<Object> mapResults = new ArrayList<>();

        // MAP phase
        List<Thread> threads = new ArrayList<>();
        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s = new Socket(worker.ip, worker.port);
                     ObjectOutputStream wOut = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream wIn = new ObjectInputStream(s.getInputStream())) {
                    wOut.writeObject(workerCommand);
                    wOut.flush();
                    Object result = wIn.readObject();
                    synchronized (mapResults) { mapResults.add(result); }
                } catch (Exception e) {
                    System.err.println("Worker " + worker.port + " error during stats: " + e.getMessage());
                    synchronized (mapResults) { mapResults.add(new HashMap<String, Double>()); }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) { try { t.join(); } catch (InterruptedException e) {} }

        // REDUCE phase
        try (Socket s = new Socket(REDUCER_IP, REDUCER_PORT);
             ObjectOutputStream rOut = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream rIn = new ObjectInputStream(s.getInputStream())) {
            rOut.writeObject("REDUCE_STATS");
            rOut.writeObject(mapResults);
            rOut.flush();
            return (Map<String, Double>) rIn.readObject();
        } catch (Exception e) {
            System.err.println("Reducer unavailable, combining locally: " + e.getMessage());
            Map<String, Double> combined = new HashMap<>();
            for (Object r : mapResults) {
                if (r instanceof Map) {
                    Map<String, Double> m = (Map<String, Double>) r;
                    for (Map.Entry<String, Double> entry : m.entrySet()) {
                        combined.put(entry.getKey(),
                            combined.getOrDefault(entry.getKey(), 0.0) + entry.getValue());
                    }
                }
            }
            return combined;
        }
    }

    private void forwardGameToWorker(Game game, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
            out.writeObject(game);
            out.flush();
        }
    }

    private void forwardStringToWorker(String message, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
            out.writeObject(message);
            out.flush();
        }
    }

    private boolean forwardStringWithResponse(String message, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {
            out.writeObject(message);
            out.flush();
            return in.readBoolean();
        }
    }

    private double forwardPlayToWorker(PlayRequest req, WorkerInfo worker) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(worker.ip, worker.port), 2000);
            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(s.getInputStream());
            out.writeObject("PLAY");
            out.flush();
            out.writeObject(req);
            out.flush();
            return in.readDouble();
        } catch (Exception e) {
            System.err.println("Master Error: Could not connect to Worker at " + worker.port + " -> " + e.getMessage());
            return 0.0;
        } finally {
            try { if (s != null) s.close(); } catch (IOException e) {}
        }
    }
}
