package com.msm.core.objects.controller;

import com.msm.core.objects.schema.ObjectSchemaService;
import com.msm.core.objects.schema.dto.ObjectSchema;
import com.msm.core.objects.schema.dto.ObjectSchemaSummary;
import com.msm.core.objects.service.GenericObjectMetadataService;
import com.msm.core.objects.service.internal.InternalGenericObjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The schema routes sit next to {@code GET /{objectName}} and {@code GET /{objectName}/{id}}: each call must reach its own handler. */
class InternalObjectSchemaRouteTest {

    private static final String BASE = "/api/v1/internal/generic/objects";

    private InternalGenericObjectService objects;
    private ObjectSchemaService schemas;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        objects = mock(InternalGenericObjectService.class);
        schemas = mock(ObjectSchemaService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new InternalGenericObjectController(objects, mock(GenericObjectMetadataService.class), schemas)).build();
    }

    @Test
    void anObjectSchemaIsNotReadAsAnId() throws Exception {
        when(schemas.getSchema("accountsite")).thenReturn(new ObjectSchema("accountsite", "id", "sha256:x", List.of()));

        mvc.perform(get(BASE + "/accountsite/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("accountsite"))
                .andExpect(jsonPath("$.hash").value("sha256:x"));

        verify(objects, never()).getObjectById(any(), any(), any());
    }

    @Test
    void theSchemaListIsNotReadAsAnObjectName() throws Exception {
        when(schemas.getSchemas()).thenReturn(List.of(new ObjectSchemaSummary("accountsite", "sha256:x")));

        mvc.perform(get(BASE + "/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].object").value("accountsite"));

        verify(objects, never()).getAllObject(any(), any());
    }

    @Test
    void anIdStillReachesTheObject() throws Exception {
        UUID id = UUID.randomUUID();
        when(objects.getObjectById(eq("accountsite"), eq(id), any())).thenReturn(Map.of("id", id.toString()));

        mvc.perform(get(BASE + "/accountsite/" + id)).andExpect(status().isOk());

        verify(objects).getObjectById(eq("accountsite"), eq(id), any());
    }
}
