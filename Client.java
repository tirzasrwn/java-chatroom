import java.awt.PrintJob;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Scanner;

public class Client {
    private static final String SERVER = "localhost";
    private static final int PORT = 3000;
    private static Socket socket;
    private static OutputStream out;

    public static final int EXIT_SUCCESS = 0;

    public static void main(String[] args) throws Exception {
        socket = new Socket(SERVER, PORT);
        out = socket.getOutputStream();

        // Perform Websocket handshake
        String key = Base64.getEncoder().encodeToString(new java.security.SecureRandom().generateSeed(16));
        String handshake = "GET /chat HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: " + key + "\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(handshake.getBytes());
        out.flush();

        // Start receiver handshake
        new Thread(Client::receiveMessage).start();

        // Get username
        Thread.sleep(500);
        System.out.println("Enter your name: ");
        String name = new Scanner(System.in).nextLine();
        sendMessage(name);

        // Read console input and send message
        while (true) {
            String message = new Scanner(System.in).nextLine();
            if (message.contains("quit")) {
                System.out.println("quiting...");
                sendClose();
                socket.close();
                System.exit(EXIT_SUCCESS);
            }
            sendMessage(message);
        }
    }

    private static void sendMessage(String message) throws IOException {
        byte[] payload = message.getBytes();
        byte[] frame = new byte[2 + 4 + payload.length];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) (0x80 | payload.length);

        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        System.arraycopy(mask, 0, frame, 2, 4);

        // Mask payload
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ mask[i % 4]);
        }

        out.write(frame);
        out.flush();
    }

    private static void receiveMessage() {
        try (InputStream in = socket.getInputStream()) {
            while (true) {
                int opcode = in.read();
                if (opcode == -1)
                    break;

                int lengthByte = in.read() & 0x7F;
                int length = lengthByte;
                if (lengthByte == 126)
                    length = (in.read() << 8) | in.read();
                else if (lengthByte == 127)
                    throw new IOException("Large message are not supported");

                byte[] payload = new byte[length];
                in.read(payload);
                System.out.println(new String(payload));
            }

        } catch (Exception e) {
            System.out.println("Disconnected from server.");
        }
    }

    private static void sendClose() throws IOException {
        byte[] closeFrame = new byte[2];
        closeFrame[0] = (byte) 0x88; // FIN + Close frame opcode
        closeFrame[1] = 0; // No payload length
        out.write(closeFrame);
        out.flush();
    }

}
