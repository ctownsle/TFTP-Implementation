package ZipProcess;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    private List <String> fileList;
    private static String SOURCE_FOLDER; // SourceFolder path

    public ZipUtils(final String source) {
        SOURCE_FOLDER = source;
        fileList = new ArrayList<>();
    }

    public void zipIt(final String zipFile) {
        System.out.println("Sending zip");
        byte[] buffer = new byte[1024];
        String source = new File(SOURCE_FOLDER).getName();
        try (FileOutputStream fos = new FileOutputStream(zipFile);
        ZipOutputStream zos = new ZipOutputStream(fos)){

            System.out.println("Output to Zip : " + zipFile);
            for (String file: this.fileList) {
                System.out.println("File Added : " + file);
                ZipEntry ze = new ZipEntry(source + File.separator + file);
                zos.putNextEntry(ze);
                try (FileInputStream in = new FileInputStream(SOURCE_FOLDER + File.separator + file)) {
                    int len;
                    while ((len = in .read(buffer)) > 0) {
                        zos.write(buffer, 0, len);
                    }
                }
            }

            zos.closeEntry();
            System.out.println("Folder successfully compressed");

        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public void generateFileList(File node) {
        // add file only
        if (node.isFile()) {
            fileList.add(generateZipEntry(node.toString()));
        }

        if (node.isDirectory()) {
            String[] subNote = node.list();
            for (String filename: subNote) {
                generateFileList(new File(node, filename));
            }
        }
    }

    private String generateZipEntry(String file) {
        return file.substring(SOURCE_FOLDER.length() + 1, file.length());
    }

}
