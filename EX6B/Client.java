import java.io.*;
import java.net.*;
class Client {
public static void main(String args[]) {
try {
BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
Socket clsct = new Socket("127.0.0.1", 3000);
BufferedReader din = new BufferedReader(new
InputStreamReader(clsct.getInputStream()));
DataOutputStream dout = new DataOutputStream(clsct.getOutputStream());
System.out.println("Enter the Physical Address (MAC):");
String str1 = in.readLine(); // Read user input// Send the MAC address to the server
dout.writeBytes(str1 + '\n');
String str = din.readLine();
System.out.println("The Logical address is (IP): " + str);
clsct.close();
} catch (Exception e) {
System.out.println(e);
}
}
}