package org.jak_linux.dns66;

import android.content.Context;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Created by jak on 07/04/17.
 */
public class FileHelperTest {
    @Test
    public void testGetItemFile() throws Exception {
        Context context = mock(Context.class);
        File file = new File("/dir/");
        when(context.getExternalFilesDir(null)).thenReturn(file);

        Configuration.Item item = new Configuration.Item();
        item.location = "http://example.com/";
        assertEquals(new File("/dir/http%3A%2F%2Fexample.com%2F"), FileHelper.getItemFile(context, item));

        item.location = "https://example.com/";
        assertEquals(new File("/dir/https%3A%2F%2Fexample.com%2F"), FileHelper.getItemFile(context, item));

        item.location = "file:/myfile";
        assertEquals(new File("/myfile"), FileHelper.getItemFile(context, item));

        item.location = "ahost.com";
        assertNull(FileHelper.getItemFile(context, item));
    }

    @Test
    public void testGetItemFile_encodingError() throws Exception {
        Context context = mock(Context.class);
        File file = new File("/dir/");
        when(context.getExternalFilesDir(null)).thenReturn(file);

        Configuration.Item item = new Configuration.Item();
        // Test encoding fails
        item.location = "https://example.com/";
        assertEquals(new File("/dir/https%3A%2F%2Fexample.com%2F"), FileHelper.getItemFile(context, item));

        // TODO: The following PowerMockito code prints the exception, but does not fail
        //mockStatic(java.net.URLEncoder.class);
        //when(java.net.URLEncoder.encode(anyString(), anyString())).thenThrow(new UnsupportedEncodingException("foo"));
        //assertNull(FileHelper.getItemFile(context, item));
    }

}