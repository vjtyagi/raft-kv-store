package com.example.networking.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import com.example.kvstore.command.DeleteCommand;
import com.example.kvstore.command.PutCommand;
import com.example.networking.config.NetworkConfig;
import com.example.node.RaftNode;

import net.bytebuddy.agent.VirtualMachine.ForHotSpot.Connection.Response;

@ExtendWith(MockitoExtension.class)
public class KVStoreControllerTest {

    @Mock
    private RaftNode raftNode;

    @Mock
    private NetworkConfig networkConfig;

    @InjectMocks
    private KVStoreController controller;

    @Test
    void testPutShouldSucceedWhenLeader() {
        when(raftNode.isLeader()).thenReturn(true);
        when(raftNode.appendCommand(any(PutCommand.class))).thenReturn(true);

        ResponseEntity<?> response = controller.put("testKey", "testValue");

        assertEquals(200, response.getStatusCode().value());
        verify(raftNode).appendCommand(any(PutCommand.class));
    }

    @Test
    void testPutShouldRedirectWhenNotLeader() {
        when(raftNode.isLeader()).thenReturn(false);
        when(raftNode.getLeaderId()).thenReturn("node2");
        when(networkConfig.getNodeUrl("node2")).thenReturn("http://node2:8082");

        ResponseEntity<?> response = controller.put("testKey", "testValue");
        assertEquals(307, response.getStatusCode().value());
        assertEquals("http://node2:8082/api/v1/kv/testKey", response.getHeaders().getLocation().toString());
    }

    @Test
    void testPutShouldReturn503WhenNoLeader() {
        when(raftNode.isLeader()).thenReturn(false);
        when(raftNode.getLeaderId()).thenReturn(null);
        ResponseEntity<?> response = controller.put("testKey", "testValue");
        assertEquals(503, response.getStatusCode().value());

        assertTrue(response.getBody().toString().contains("No leader available"));
    }

    @Test
    void testGetShouldReturnValue() {
        ResponseEntity<?> response = controller.get("testKey");
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }

    @Test
    void testDeleteShouldSucceedWhenLeader() {
        when(raftNode.isLeader()).thenReturn(true);
        when(raftNode.appendCommand(any(DeleteCommand.class))).thenReturn(true);

        ResponseEntity<?> response = controller.delete("testKey");
        assertEquals(200, response.getStatusCode().value());
        verify(raftNode).appendCommand(any(DeleteCommand.class));
    }

    @Test
    void testDeleteShouldReturn404WhenKeyNotFound() {
        when(raftNode.isLeader()).thenReturn(true);
        when(raftNode.appendCommand(any(DeleteCommand.class))).thenReturn(false);

        ResponseEntity<?> response = controller.delete("testKey");
        assertEquals(404, response.getStatusCode().value());
    }
}
