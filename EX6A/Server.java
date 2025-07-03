import java.io.*;
import java.net.*;

class Server {
    public static void main(String args[]) {
        try {
            ServerSocket obj = new ServerSocket(3636);
            Socket obj1 = obj.accept();
            BufferedReader din = new BufferedReader(
                new InputStreamReader(obj1.getInputStream())
            );
            DataOutputStream dout = new DataOutputStream(obj1.getOutputStream());

            String[] ip = {"165.165.80.80", "165.165.79.1"};
            String[] mac = {"6A:08:AA:C2", "8A:BC:E3:FA"};

            while (true) {
                String str = din.readLine();
                if (str == null) {
                    break;
                }

                boolean found = false;
                for (int i = 0; i < ip.length; i++) {
                    if (str.equals(ip[i])) {
                        dout.writeBytes(mac[i] + '\n');
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    dout.writeBytes("MAC not found\n");
                }
            }

            obj.close();
        } catch (Exception e) {
            System.out.println(e);
        }
    }
}
