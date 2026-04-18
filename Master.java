import java.io.*;
import java.net.*;
import java.util.*;

// για αναζήτηση και στατιστικά εκτελεί MapReduce (Map στους Workers, Reduce στον Reducer)
public class Master {
    private static final int    PORT         = 9000;
    private static final String REDUCER_IP   = "localhost";
    private static final int    REDUCER_PORT = 8500;

    private List<WorkerInfo> workers = new ArrayList<>(); // λίστα με όλους τους Workers

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Master master = new Master();
        if (args.length == 0) {
            System.out.println("Χρήση: java Master <port_worker1> <port_worker2> ...");
            return;
        }
        for (String port : args) {
            master.workers.add(new WorkerInfo("127.0.0.1", Integer.parseInt(port)));
        }
        master.startServer();
    }

    // ξεκινάει τον Master server κάθε client εξυπηρετείται σε ξεχωριστό thread
    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Ο Master Server ακούει στη θύρα " + PORT + "...");
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(new ClientHandler(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // χειρίζεται ένα αίτημα client αναγνωρίζει τον τύπο και το προωθεί στον κατάλληλο Worker
    private class ClientHandler implements Runnable {
        private Socket socket;
        public ClientHandler(Socket socket) { this.socket = socket; }

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try (ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
                 ObjectInputStream  in  = new ObjectInputStream(socket.getInputStream())) {

                Object request = in.readObject();

                if (request instanceof Game) {
                    // hashing για να αποφασίσω ποιος Worker παίρνει αυτό το παιχνίδι
                    Game newGame = (Game) request;
                    int workerIndex = Math.abs(newGame.gameName.hashCode()) % workers.size();
                    forwardGameToWorker(newGame, workers.get(workerIndex));
                    System.out.println("Παιχνίδι " + newGame.gameName + " -> Worker " + workers.get(workerIndex).port);

                } else if (request instanceof SearchFilters) {
                    // αναζήτηση με MapReduce στέλνω φίλτρα σε ΟΛΟΥΣ τους Workers και ο Reducer ενώνει
                    System.out.println("MapReduce Search...");
                    List<Game> results = performMapReduceSearch((SearchFilters) request);
                    out.writeObject(results);
                    out.flush();

                } else if (request instanceof PlayRequest) {
                    // hashing για να βρω ποιος Worker έχει το συγκεκριμενο παιχνίδι
                    PlayRequest playReq = (PlayRequest) request;
                    System.out.println("Ποντάρισμα για: " + playReq.gameName);
                    int workerIndex = Math.abs(playReq.gameName.hashCode()) % workers.size();
                    double winAmount = forwardPlayToWorker(playReq, workers.get(workerIndex));
                    out.writeDouble(winAmount);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("REMOVE_GAME:")) {
                    // hashing για τον σωστό Worker αυτός που έχει αποθηκευμένο το παιχνίδι
                    String gameName = ((String) request).substring("REMOVE_GAME:".length());
                    int workerIndex = Math.abs(gameName.hashCode()) % workers.size();
                    forwardStringToWorker((String) request, workers.get(workerIndex));
                    System.out.println("Αίτημα διαγραφής → " + gameName);

                } else if (request instanceof String && ((String) request).startsWith("MODIFY_RISK:")) {
                    // προωθώ στον σωστό Worker και περιμένω boolean επιβεβαίωση
                    String[] parts = ((String) request).split(":", 3);
                    String gameName = parts[1];
                    int workerIndex = Math.abs(gameName.hashCode()) % workers.size();
                    boolean success = forwardStringWithResponse((String) request, workers.get(workerIndex));
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("RATE_GAME:")) {
                    // hashing, προωθώ και περιμένω boolean επιβεβαίωση
                    String[] parts = ((String) request).split(":", 3);
                    String gameName = parts[1];
                    int workerIndex = Math.abs(gameName.hashCode()) % workers.size();
                    boolean success = forwardStringWithResponse((String) request, workers.get(workerIndex));
                    out.writeBoolean(success);
                    out.flush();

                } else if (request instanceof String && request.equals("VIEW_PROFITS")) {
                    // στατιστικά κερδών ανά παιχνίδι MapReduce με GET_STATS
                    Map<String, Double> stats = performMapReduceStats("GET_STATS");
                    out.writeObject(stats);
                    out.flush();

                } else if (request instanceof String && ((String) request).startsWith("VIEW_PROFITS_PLAYER:")) {
                    // στατιστικά ζημιών για έναν συγκεκριμένο παίκτη
                    String playerId = ((String) request).substring("VIEW_PROFITS_PLAYER:".length());
                    Map<String, Double> stats = performMapReduceStats("GET_PLAYER_STATS:" + playerId);
                    out.writeObject(stats);
                    out.flush();

                }

            } catch (Exception e) {
                System.err.println("Master Σφάλμα: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (IOException e) {}
            }
        }
    }

    // MAP: στέλνει τα φίλτρα σε κάθε Worker παράλληλα (ένα thread ανά Worker) και συλλέγει τα αποτελέσματα
    // REDUCE: στέλνει όλα τα αποτελέσματα στον Reducer για να τα ενώσει σε μία λίστα
    @SuppressWarnings("unchecked")
    private List<Game> performMapReduceSearch(SearchFilters filters) {
        final List<Object> mapResults = new ArrayList<>(); // μαζεύω εδώ τα αποτελέσματα από κάθε Worker

        List<Thread> threads = new ArrayList<>();
        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s       = new Socket(worker.ip, worker.port);
                     ObjectOutputStream wOut = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream  wIn  = new ObjectInputStream(s.getInputStream())) {
                    wOut.writeObject(filters);
                    wOut.flush();
                    Object result = wIn.readObject(); // List<Game> από τον Worker
                    synchronized (mapResults) { mapResults.add(result); }
                } catch (Exception e) {
                    System.err.println("Worker " + worker.port + " σφάλμα αναζήτησης: " + e.getMessage());
                    synchronized (mapResults) { mapResults.add(new ArrayList<Game>()); }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) { try { t.join(); } catch (InterruptedException e) {} }

        // στέλνω όλα τα αποτελέσματα στον Reducer για την τελική ένωση
        try (Socket s = new Socket(REDUCER_IP, REDUCER_PORT);
             ObjectOutputStream rOut = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream  rIn  = new ObjectInputStream(s.getInputStream())) {
            rOut.writeObject("REDUCE_SEARCH");
            rOut.writeObject(mapResults); // λίστα από List<Game> μία ανά Worker
            rOut.flush();
            return (List<Game>) rIn.readObject();
        } catch (Exception e) {
            // αν ο Reducer δεν είναι διαθέσιμος, κάνω το reduce εδώ τοπικά
            System.err.println("Reducer μη διαθέσιμος, τοπικός συνδυασμός: " + e.getMessage());
            List<Game> combined = new ArrayList<>();
            for (Object r : mapResults) {
                if (r instanceof List) combined.addAll((List<Game>) r);
            }
            return combined;
        }
    }

    // MAP: στέλνει εντολή στατιστικών σε κάθε Worker παράλληλα
    // REDUCE: στέλνει τα Map<String,Double> στον Reducer για άθροιση
    @SuppressWarnings("unchecked")
    private Map<String, Double> performMapReduceStats(String workerCommand) {
        final List<Object> mapResults = new ArrayList<>(); // μαζεύω εδώ τα Map<String,Double> από κάθε Worker

        List<Thread> threads = new ArrayList<>();
        for (WorkerInfo worker : workers) {
            Thread t = new Thread(() -> {
                try (Socket s       = new Socket(worker.ip, worker.port);
                     ObjectOutputStream wOut = new ObjectOutputStream(s.getOutputStream());
                     ObjectInputStream  wIn  = new ObjectInputStream(s.getInputStream())) {
                    wOut.writeObject(workerCommand);
                    wOut.flush();
                    Object result = wIn.readObject(); // Map<String,Double> από τον Worker
                    synchronized (mapResults) { mapResults.add(result); }
                } catch (Exception e) {
                    System.err.println("Worker " + worker.port + " σφάλμα stats: " + e.getMessage());
                    synchronized (mapResults) { mapResults.add(new HashMap<String, Double>()); }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) { try { t.join(); } catch (InterruptedException e) {} }

        try (Socket s = new Socket(REDUCER_IP, REDUCER_PORT);
             ObjectOutputStream rOut = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream  rIn  = new ObjectInputStream(s.getInputStream())) {
            rOut.writeObject("REDUCE_STATS");
            rOut.writeObject(mapResults); // λίστα από Map<String,Double>
            rOut.flush();
            return (Map<String, Double>) rIn.readObject();
        } catch (Exception e) {
            // fallback αν ο Reducer δεν απαντάει
            System.err.println("Reducer μη διαθέσιμος, τοπικός συνδυασμός: " + e.getMessage());
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

    // στέλνει ένα Game αντικείμενο σε έναν Worker χωρίς να περιμένω απάντηση
    private void forwardGameToWorker(Game game, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
            out.writeObject(game);
            out.flush();
        }
    }

    // στέλνει String εντολή σε έναν Worker παλι χωρις να περιμενω καποια απαντηση
    private void forwardStringToWorker(String message, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream())) {
            out.writeObject(message);
            out.flush();
        }
    }

    // στέλνει String εντολή και λαμβάνει boolean τιμη, true αν ο Worker βρήκε και άλλαξε το παιχνίδι, false αν οχι
    private boolean forwardStringWithResponse(String message, WorkerInfo worker) throws IOException {
        try (Socket s = new Socket(worker.ip, worker.port);
             ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
             ObjectInputStream  in  = new ObjectInputStream(s.getInputStream())) {
            out.writeObject(message);
            out.flush();
            return in.readBoolean();
        }
    }

    // στέλνει PlayRequest στον Worker και λαμβάνει πόσα κέρδισε ο παίκτης
    // έχω timeout 2 δευτερολέπτων για να μην κολλάει ο Master αν ο Worker δεν απαντάει
    private double forwardPlayToWorker(PlayRequest req, WorkerInfo worker) {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(worker.ip, worker.port), 2000); // timeout σύνδεσης
            ObjectOutputStream out = new ObjectOutputStream(s.getOutputStream());
            out.flush();
            ObjectInputStream in = new ObjectInputStream(s.getInputStream());
            out.writeObject("PLAY"); // πρώτα στέλνω τον τύπο αιτήματος
            out.flush();
            out.writeObject(req);   // μετά το PlayRequest
            out.flush();
            return in.readDouble();
        } catch (Exception e) {
            System.err.println("Master: Αδύνατη σύνδεση με Worker " + worker.port + " → " + e.getMessage());
            return 0.0;
        } finally {
            try { if (s != null) s.close(); } catch (IOException e) {}
        }
    }
}
