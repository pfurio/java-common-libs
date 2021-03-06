package org.opencb.commons.run;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.opencb.commons.io.DataWriter;
import org.opencb.commons.io.StringDataReader;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class ParallelTaskRunnerTest {


    protected static final int lines = 10000;
    protected static final String fileName = "/tmp/dummyFile.txt";
    protected static final String outputFileName = "/tmp/output.log";

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void beforeClass() throws IOException {
        FileOutputStream fileOutputStream = new FileOutputStream(fileName);

        for (int l = 0; l < lines; l++) {
            fileOutputStream.write(new StringBuilder()
                    .append(RandomStringUtils.randomAlphanumeric(RandomUtils.nextInt(0, 16))).append(" ")
                    .append(RandomStringUtils.randomAlphanumeric(RandomUtils.nextInt(0, 16))).append(" ")
                    .append(RandomStringUtils.randomAlphanumeric(RandomUtils.nextInt(0, 16))).append("\n")
                    .toString().getBytes());
        }
    }

    @AfterClass
    public static void afterClass() throws IOException {
        if (Files.exists(Paths.get(fileName))) {
            Files.delete(Paths.get(fileName));
        }
        if (Files.exists(Paths.get(outputFileName))) {
            Files.delete(Paths.get(outputFileName));
        }
    }

    final Long[] l = {0l, 0l, 0l};
    ParallelTaskRunner.Task<String, Integer> wc = strings -> {
        List<Integer> list = new ArrayList<>(strings.size());
        long lines = 0, words = 0, chars = 0;
        for (String string : strings) {
            if ((lines & 63) == 0) {
                System.out.println("->" + (l[0]+lines));
            }
            list.add(string.length());
            lines++;                                     //lines
            words += string.split("[\n\t ]").length;     //words
            chars += string.length() + 1;                //chars
            for (int i = 1; i < 100; i++) {
                int ignored = (int)(string.length()*0.5+5*Math.log10(string.split("[\n\t ]").length*50.0*Math.abs(Math.sin(l[1]))));  //stupid operation
            }
        }
        synchronized (l) {
            l[0] += lines;
            l[1] += words;
            l[2] += chars;
        }
        return list;
    };

    @Test
    public void test() throws Exception {
        ParallelTaskRunner.Config config = new ParallelTaskRunner.Config(8, 100, 10, false);

        l[0] = l[1] = l[2] = 0l;
        Path path = Paths.get(fileName);


        DataOutputStream os = new DataOutputStream(new FileOutputStream(outputFileName));
        DataWriter<Integer> dataWriter = batch -> {
            for (Integer integer : batch) {
                try {
                    os.writeBytes(integer + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }
            }
            return true;
        };

        ParallelTaskRunner<String, Integer> runner = new ParallelTaskRunner<>(new StringDataReader(path), wc, dataWriter, config);
        runner.run();
        System.out.println("WC : " + l[0] + " " + l[1] + " " + l[2]);

        os.close();

    }



    @Test
    public void testWithoutReader() throws Exception {
        ParallelTaskRunner.Config config = new ParallelTaskRunner.Config(8, 100, 10, false);

        l[0] = l[1] = l[2] = 0l;
        Path path = Paths.get(fileName);
        final int[] generatedLines = {0};

        ParallelTaskRunner.Task<String, Integer> generateAndwc = strings -> {
            //Generate data
            int linesToGenerate;
            synchronized (config) {
                linesToGenerate = lines - generatedLines[0] > 100 ? 100 : Math.max(lines - generatedLines[0], 0);
                generatedLines[0] += linesToGenerate;
            }
            strings = new ArrayList<>(linesToGenerate);
            for (int i = 0; i < linesToGenerate; i++) {
                strings.add(new StringBuilder()
                        .append(RandomStringUtils.randomAlphanumeric(RandomUtils.nextInt(0, 16))).append(" ")
                        .append(RandomStringUtils.randomAlphanumeric(RandomUtils.nextInt(0, 16))).append(" ")
                        .append(RandomStringUtils.randomAlphanumeric(RandomUtils.nextInt(0, 16))).append("\n")
                        .toString());
            }
            return wc.apply(strings);
        };

        DataOutputStream os = new DataOutputStream(new FileOutputStream(outputFileName));
        DataWriter<Integer> dataWriter = batch -> {
            for (Integer integer : batch) {
                try {
                    os.writeBytes(integer + "\n");
                } catch (IOException e) {
                    e.printStackTrace();
                    return false;
                }
            }
            return true;
        };

        ParallelTaskRunner<String, Integer> runner = new ParallelTaskRunner<>(null, generateAndwc, dataWriter, config);
        runner.run();
        System.out.println("WC : " + l[0] + " " + l[1] + " " + l[2]);

        os.close();

    }


    @Test
    public void testTimeOut() throws Exception {
        final AtomicInteger i = new AtomicInteger(0);
        ParallelTaskRunner<String, Void> runner = new ParallelTaskRunner<>(
                (size) -> {
                    return Collections.singletonList(RandomStringUtils.randomAlphanumeric(RandomUtils.nextInt(0, 16)));
                },
                (batch) -> {
                    try {
                        if (i.addAndGet(1) > 100) {
                            System.out.println(Thread.currentThread().getName() + " -- sleeping 5s");
                            Thread.sleep(5000L);
                        } else {
                            System.out.println(Thread.currentThread().getName() + " -- don't sleep! " + i.get());
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    return null;
                },
                null,
                new ParallelTaskRunner.Config(5, 1, 2, true, false, 2)
        );

        thrown.expect(ExecutionException.class);
        runner.run();
    }

}