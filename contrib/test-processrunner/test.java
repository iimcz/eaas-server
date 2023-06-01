import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import de.bwl.bwfla.common.utils.DeprecatedProcessRunner;

public class test {
    public static void main(String[] args) throws Exception {
        System.out.println(new DeprecatedProcessRunner()
                .setCommand("head")
                .addArguments("-c", String.valueOf(16 * 4096), "/dev/zero")
                .executeWithResult().orElse(null).stdout().length());

        // See "Pipe capacity" in <https://man7.org/linux/man-pages/man7/pipe.7.html>
        System.out.println(new DeprecatedProcessRunner()
                .setCommand("head")
                .addArguments("-c", String.valueOf(16 * 4096 + 1), "/dev/zero")
                .executeWithResult().orElse(null).stdout().length());
    }
}
