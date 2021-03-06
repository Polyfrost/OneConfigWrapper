package cc.polyfrost.oneconfigwrapper;

import cc.polyfrost.oneconfigwrapper.ssl.SSLStore;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.common.ForgeVersion;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

public class OneConfigWrapper implements ITweaker {
    public static final Color GRAY_900 = new Color(13, 14, 15, 255);
    public static final Color PRIMARY_500 = new Color(26, 103, 255);
    public static final Color PRIMARY_500_80 = new Color(26, 103, 204);
    public static final Color WHITE_80 = new Color(255, 255, 255, 204);
    public static final Color TRANSPARENT = new Color(0, 0, 0, 0);
    private ITweaker loader = null;

    public OneConfigWrapper() {
        try {
            try {
                SSLStore sslStore = new SSLStore();
                System.out.println("Attempting to load Polyfrost certificate.");
                sslStore = sslStore.load("/ssl/polyfrost.der");
                SSLContext context = sslStore.finish();
                SSLContext.setDefault(context);
                HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Failed to add Polyfrost certificate to keystore.");
            }

            File oneConfigDir = new File(new File(Launch.minecraftHome, "OneConfig"), "launchwrapper");
            if (!oneConfigDir.exists() && !oneConfigDir.mkdirs())
                throw new IllegalStateException("Could not create OneConfig dir!");

            File oneConfigLoaderFile = new File(oneConfigDir, "OneConfig-Loader.jar");

            if (!isInitialized(oneConfigLoaderFile)) {
                Object mcVersion = "1.8.9";
                try {
                    mcVersion = ForgeVersion.class.getDeclaredField("mcVersion").get(null);
                    System.out.println("OneConfig has detected the version " + mcVersion + ". If this is false, report this at https://inv.wtf/polyfrost");
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println("Getting the Minecraft version failed, defaulting to 1.8.9. Please report this to https://inv.wtf/polyfrost");
                }
                JsonElement json = getRequest("https://api.polyfrost.cc/oneconfig/" + mcVersion + "-forge");

                if (json != null && json.isJsonObject()) {
                    JsonObject jsonObject = json.getAsJsonObject();

                    if (jsonObject.has("loader") && jsonObject.getAsJsonObject("loader").has("url")
                            && jsonObject.getAsJsonObject("loader").has("sha256")) {

                        String checksum = jsonObject.getAsJsonObject("loader").get("sha256").getAsString();
                        String downloadUrl = jsonObject.getAsJsonObject("loader").get("url").getAsString();

                        if (!oneConfigLoaderFile.exists() || !checksum.equals(getChecksum(oneConfigLoaderFile))) {
                            System.out.println("Updating OneConfig Loader...");
                            File newLoaderFile = new File(oneConfigDir, "OneConfig-Loader-NEW.jar");

                            downloadFile(downloadUrl, newLoaderFile);

                            if (newLoaderFile.exists() && checksum.equals(getChecksum(newLoaderFile))) {
                                try {
                                    Files.move(newLoaderFile.toPath(), oneConfigLoaderFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                    System.out.println("Updated OneConfig loader");
                                } catch (IOException ignored) {
                                }
                            } else {
                                if (newLoaderFile.exists()) newLoaderFile.delete();
                                System.out.println("Failed to update OneConfig loader, trying to continue...");
                            }
                        }
                    }
                }

                if (!oneConfigLoaderFile.exists()) showErrorScreen();
                addToClasspath(oneConfigLoaderFile);
            }
            loader = ((ITweaker) Launch.classLoader.findClass("cc.polyfrost.oneconfigloader.OneConfigLoader").newInstance());
        } catch (Exception e) {
            e.printStackTrace();
            showErrorScreen();
        }
    }

    private boolean isInitialized(File file) {
        try {
            URL url = file.toURI().toURL();
            return Arrays.asList(((URLClassLoader) Launch.classLoader.getClass().getClassLoader()).getURLs()).contains(url);
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private void addToClasspath(File file) {
        try {
            URL url = file.toURI().toURL();
            Launch.classLoader.addURL(url);
            ClassLoader classLoader = Launch.classLoader.getClass().getClassLoader();
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(classLoader, url);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void downloadFile(String url, File location) {
        try {
            URLConnection con = new URL(url).openConnection();
            con.setRequestProperty("User-Agent", "OneConfig-Wrapper");
            InputStream in = con.getInputStream();
            Files.copy(in, location.toPath(), StandardCopyOption.REPLACE_EXISTING);
            in.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static JsonElement getRequest(String site) {
        try {
            URL url = new URL(site);
            HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            con.setRequestProperty("User-Agent", "OneConfig-Wrapper");
            con.setRequestMethod("GET");
            int status = con.getResponseCode();
            if (status != 200) {
                System.out.println("API request failed, status code " + status);
                return null;
            }
            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            JsonParser parser = new JsonParser();
            return parser.parse(content.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String getChecksum(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
            byte[] digested = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digested) {
                sb.append(Integer.toString((b & 0xff) + 0x100, 16).substring(1));
            }
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    private void showErrorScreen() {
        try {
            UIManager.put("OptionPane.background", GRAY_900);
            UIManager.put("Panel.background", GRAY_900);
            UIManager.put("OptionPane.messageForeground", WHITE_80);
            UIManager.put("Button.background", PRIMARY_500);
            UIManager.put("Button.select", PRIMARY_500_80);
            UIManager.put("Button.foreground", WHITE_80);
            UIManager.put("Button.focus", TRANSPARENT);
            int response = JOptionPane.showOptionDialog(
                    null,
                    "OneConfig has failed to download!\n" +
                            "Please join our discord server at https://polyfrost.cc/discord\n" +
                            "for support, or try again later.",
                    "OneConfig has failed to download!", JOptionPane.OK_CANCEL_OPTION, JOptionPane.ERROR_MESSAGE, null,
                    new Object[]{"Join Discord", "Close"}, "Join Discord"
            );
            if (response == 0) {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new URI("https://polyfrost.cc/discord"));
                }
            }
        } catch (Exception ignored) {
        } finally {
            try {
                Method exit = Class.forName("java.lang.Shutdown").getDeclaredMethod("exit", int.class);
                exit.setAccessible(true);
                exit.invoke(null, 1);
            } catch (Exception e) {
                System.exit(1);
            }
        }
    }

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        if (loader != null) loader.acceptOptions(args, gameDir, assetsDir, profile);
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        if (loader != null) loader.injectIntoClassLoader(classLoader);
    }

    @Override
    public String getLaunchTarget() {
        return loader != null ? loader.getLaunchTarget() : null;
    }

    @Override
    public String[] getLaunchArguments() {
        return loader != null ? loader.getLaunchArguments() : new String[0];
    }
}
