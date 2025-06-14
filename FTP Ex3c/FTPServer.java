import java.io.*;
import java.net.*;

public class FTPServer {
    public static void main(String args[]) throws Exception {
        ServerSocket ss = new ServerSocket(1024);
        System.out.println("ServerSocket Generated");
        Socket s = ss.accept();
        System.out.println("ServerSocket Accepted");

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        PrintStream p = new PrintStream(s.getOutputStream());
        String fname, str;

        System.out.println("Enter a File Name:");
        fname = br.readLine();
        File f1 = new File(fname);

        if (f1.exists()) {
            BufferedReader br1 = new BufferedReader(new FileReader(fname));
            while ((str = br1.readLine()) != null)
                p.println(str);
        }

        p.close();
        s.close();
        ss.close();
    }
}
