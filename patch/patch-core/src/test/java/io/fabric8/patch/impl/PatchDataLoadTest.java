package io.fabric8.patch.impl;

import io.fabric8.patch.Service;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;

/**
 * Created by gert on 14/11/14.
 */
public class PatchDataLoadTest {

    private Service service;

    @Before
    public void createMockService() {
        service = createMock(Service.class);
        replay(service);
    }

    @Test
    public void testLoadWithFileOverrides() throws IOException {
        PatchData patch = PatchData.load(getClass().getClassLoader().getResourceAsStream("files/patch1.patch"));
        assertEquals(1, patch.getBundles().size());
        assertEquals(1, patch.getFiles().size());
        assertEquals("bin/karaf", patch.getFiles().get("${karaf.home}/bin/karaf"));
    }

}
