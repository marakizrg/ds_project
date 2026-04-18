// κρατάει το IP και το port ενός Worker και ο Master τη χρησιμοποιεί για να ξέρει πού να συνδεθεί
public class WorkerInfo {
    String ip;
    int port;

    public WorkerInfo(String ip, int port) {
        this.ip   = ip;
        this.port = port;
    }
}
