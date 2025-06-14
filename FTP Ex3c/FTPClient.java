import java.io.*;
import java.net.*;

public class FTPClient {
    public static void main(String args[]) throws Exception {
        InetAddress ia = InetAddress.getLocalHost();
        Socket s = new Socket(ia, 1024);
        String fname, str;

        System.out.println("Enter a new File Name to save:");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        fname = br.readLine();

        PrintWriter p = new PrintWriter(new FileWriter(fname));
        BufferedReader br1 = new BufferedReader(new InputStreamReader(s.getInputStream()));

        while ((str = br1.readLine()) != null)
            p.println(str);

        p.close();
        s.close();
    }
}
