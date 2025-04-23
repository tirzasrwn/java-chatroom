import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Server {

    private static final int PORT = 3000;
    private static final Set<ClientInfo> clients = Collections.synchronizedSet(new HashSet<>());

    // Inner class to store client information, like struct in c
    private static class ClientInfo {
        Socket socket;
        String name;
        boolean hasName = false;

        ClientInfo(Socket socket) {
            this.socket = socket;
        }
    }

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Server started on port: " + PORT);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            ClientInfo clientInfo = new ClientInfo(clientSocket);
            clients.add(clientInfo);
            new Thread(() -> handleClient(clientInfo)).start();
        }

    }

    private static void handleClient(ClientInfo clientInfo) {
        try (
                InputStream input = clientInfo.socket.getInputStream();
                OutputStream output = clientInfo.socket.getOutputStream()) {

            // Websocket Handshake
            String key = performHandshake(input, output);

            // Listen for messages
            while (!clientInfo.socket.isClosed()) {
                ByteBuffer frame = readWebsocketFrame(input);
                if (frame == null)
                    break;

                String message = new String(frame.array(), frame.position(), frame.remaining());

                if (!clientInfo.hasName) {
                    // First message is the client's name
                    clientInfo.name = message;
                    clientInfo.hasName = true;
                    System.out.println(clientInfo.name + " joined");
                    sendPrivate("Welcome, " + clientInfo.name + "!", clientInfo);
                    broadcast(clientInfo.name + " joined", clientInfo.socket);
                } else {
                    // NEW: Check for private message format "name: message"
                    if (message.contains(":")) {
                        int colonIndex = message.indexOf(':');
                        String recipientName = message.substring(0, colonIndex).trim();
                        String privateMessage = message.substring(colonIndex + 1).trim();

                        sendPrivateMessage(
                                clientInfo.name,
                                recipientName,
                                privateMessage,
                                clientInfo);
                    } else {
                        // Broadcast public message
                        broadcast(clientInfo.name + ": " + message, clientInfo.socket);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                broadcast(clientInfo.name + " has disconnected.", clientInfo.socket);
                System.out.println(clientInfo.name + " has disconnected.");
            } catch (IOException e) {
                e.printStackTrace();
            }
            clients.remove(clientInfo);
        }
    }

    private static void sendPrivate(String message, ClientInfo client) throws IOException {
        byte[] frame = createFrame(message.getBytes());
        client.socket.getOutputStream().write(frame);
    }

    private static void sendPrivateMessage(String senderName, String recipientName, String message, ClientInfo sender)
            throws IOException {
        boolean recipientFound = false;

        synchronized (clients) {
            for (ClientInfo client : clients) {
                if (client.hasName && client.name.equals(recipientName)) {
                    try {
                        // Send to recipient
                        String formatted = "(Private) " + senderName + ": " + message;
                        client.socket.getOutputStream().write(createFrame(formatted.getBytes()));

                        // Send confirmation to sender
                        String confirmation = "To " + recipientName + ": " + message;
                        sender.socket.getOutputStream().write(createFrame(confirmation.getBytes()));

                        recipientFound = true;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
            if (!recipientFound) {
                try {
                    String error = "User '" + recipientName + "' not found!";
                    sender.socket.getOutputStream().write(createFrame(error.getBytes()));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static ByteBuffer readWebsocketFrame(InputStream input) throws IOException {
        int firstByte = input.read();
        if (firstByte == -1)
            return null;

        int secondByte = input.read();
        boolean masked = (secondByte & 0x80) != 0;
        int payloadLength = secondByte & 0x7F;

        if (payloadLength == 126)
            payloadLength = (input.read() << 8) | input.read();
        else if (payloadLength == 127)
            throw new IOException("Large payload not supported");

        byte[] maskingKey = new byte[4];
        if (masked)
            input.read(maskingKey);

        byte[] payload = new byte[payloadLength];
        input.read(payload);

        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskingKey[i % 4];
            }
        }

        return ByteBuffer.wrap(payload);
    }

    private static String performHandshake(InputStream input, OutputStream output) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input));
        String line;
        String key = null;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            if (line.startsWith("Sec-Websocket-Key")) {
                key = line.split(":")[1].trim();
            }
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes()));

        String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        output.write(response.getBytes());
        output.flush();
        return key;
    }

    private static void broadcast(String message, Socket sender) throws IOException {
        byte[] frame = createFrame(message.getBytes());
        synchronized (clients) {
            for (ClientInfo client : clients) {
                if (client.socket != sender && client.socket.isConnected()) {
                    client.socket.getOutputStream().write(frame);
                }
            }
        }
    }

    private static byte[] createFrame(byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(2 + payload.length);
        buffer.put((byte) 0x81); // Text frame (FIN + opcode)
        buffer.put((byte) payload.length);
        buffer.put(payload);
        return buffer.array();

    }
}
