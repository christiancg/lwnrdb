package org.techhouse.unit.conn;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.conn.MessageProcessor;

import java.io.*;
import java.net.InetAddress;
import java.net.Socket;

import static org.mockito.Mockito.*;

public class MessageProcessorTest {
    // Handles null or blank messages gracefully
    @Test
    public void test_handles_null_or_blank_messages() throws Exception {
        Socket mockSocket = Mockito.mock(Socket.class);
        InetAddress mockInetAddress = Mockito.mock(InetAddress.class);
        Mockito.when(mockSocket.getInetAddress()).thenReturn(mockInetAddress);
        Mockito.when(mockInetAddress.getHostAddress()).thenReturn("127.0.0.1");
        BufferedReader mockReader = mock(BufferedReader.class);
        BufferedWriter mockWriter = mock(BufferedWriter.class);
        when(mockSocket.getInputStream()).thenReturn(new ByteArrayInputStream("".getBytes()));
        when(mockSocket.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(mockReader.readLine()).thenReturn("").thenReturn(null);
    
        MessageProcessor messageProcessor = new MessageProcessor(mockSocket);
        Thread thread = new Thread(messageProcessor);
        thread.start();
    
        verify(mockWriter, never()).write(anyString());
        verify(mockWriter, never()).flush();
    }
}