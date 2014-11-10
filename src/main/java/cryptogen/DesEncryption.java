package cryptogen;

import helpers.ByteHelper;
import helpers.ConsoleHelper;

import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/**
 * @author Peter.
 */
public class DesEncryption {

    private static final int blockSizeInBytes = 8;

    
    private static final int[] initialPermutation = new int[] {
        58, 50, 42, 34, 26, 18, 10, 2,
        60, 52, 44, 36, 28, 20, 12, 4,
        62, 54, 46, 38, 30, 22, 14, 6,
        64, 56, 48, 40, 32, 24, 16, 8,
        57, 49, 41, 33, 25, 17, 9, 1,
        59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5,
        63, 55, 47, 39, 31, 23, 15, 7
    };

    private static final int[] inverseInitialPermutation = new int[] {
        40, 8, 48, 16, 56, 24, 64, 32,
        39, 7, 47, 15, 55, 23, 63, 31,
        38, 6, 46, 14, 54, 22, 62, 30,
        37, 5, 45, 13, 53, 21, 61, 29,
        36, 4, 44, 12, 52, 20, 60, 28,
        35, 3, 43, 11, 51, 19, 59, 27,
        34, 2, 42, 10, 50, 18, 58, 26,
        33, 1, 41, 9, 49, 17, 57, 25
    };

      public static void encryptFile3DES(String inputFilePath, String outputFilePath, String key) {
        List<byte[][]> subKeys = KeyCalculator.generateFor3DES(key);
        
        String tempFile = outputFilePath + ".temp";
        encryptFileAsync(inputFilePath, outputFilePath, subKeys.get(2));
        decryptFileAsync(outputFilePath, tempFile, subKeys.get(1));
        encryptFileAsync(tempFile, outputFilePath, subKeys.get(0));
        
        File temp = new File(tempFile);
        temp.delete();
    }

    public static void decryptFile3DES(String inputFilePath, String outputFilePath, String key) {
        
        List<byte[][]> subKeys = KeyCalculator.generateFor3DES(key);
        String tempFile = outputFilePath + ".temp";

        decryptFileAsync(inputFilePath, outputFilePath, subKeys.get(0));
        encryptFileAsync(outputFilePath, tempFile, subKeys.get(1));
        decryptFileAsync(tempFile, outputFilePath, subKeys.get(2));
        
        File temp = new File(tempFile);
        temp.delete();
    }
    
    public static void encryptFile(String inputFilePath, String outputFilePath, String key) {
        final byte[][] subKeys = KeyCalculator.generate(key);
        encryptFileAsync(inputFilePath, outputFilePath, subKeys);
        
    }

    public static void decryptFile(String inputFilePath, String outputFilePath, String key) { 
        byte[][] subKeys = KeyCalculator.generate(key);
        decryptFileAsync(inputFilePath, outputFilePath, subKeys);
    }
    
    
    private static void encryptFileAsync(String inputFilePath, String outputFilePath, byte[][] subKeys) {
        System.out.println();
        System.out.println("Encrypting Async");

        try {
            final File inputFile = new File(inputFilePath);
            final File outputFile = new File(outputFilePath);
            final InputStream inputStream = new BufferedInputStream(new FileInputStream(inputFile));
            final OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile));

            final List<Future<byte[]>> futures = new ArrayList<>();
            final ExecutorService executor = Executors.newFixedThreadPool(4);

            final long nbBytesFile = inputFile.length();
            final long nbTotalBlocks = (long) Math.ceil(nbBytesFile / (double) blockSizeInBytes);
            final int nbBytesPaddingNeeded = (int) (blockSizeInBytes - (nbBytesFile % blockSizeInBytes));

            final byte header = (byte) nbBytesPaddingNeeded;
            outputStream.write(header);

            long before = System.nanoTime();

            byte[] block = new byte[blockSizeInBytes];
            int bytesRead = 0;
            for (int nbBlocks = 1; nbBlocks <= nbTotalBlocks; nbBlocks++) {

                bytesRead = inputStream.read(block);

                final byte[] finalBlock = block;
                //System.out.println("Encrypting block async " + nbBlocks);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    return encryptBlock(finalBlock, subKeys);
                }, executor).exceptionally(ex -> {
                    throw (RuntimeException) ex;
                }));

                block = new byte[blockSizeInBytes];
            }
            inputStream.close();

            System.out.println("Done setting tasks");
            long afterTasks = System.nanoTime();

            futures.stream().forEachOrdered(encryptedBlock -> {
                try {
                    outputStream.write(encryptedBlock.get());
                    //System.out.println("Blok weggeschreven");
                } catch (InterruptedException | ExecutionException | IOException e) {
                    throw new RuntimeException(e);
                }
            });

            long afterWriting = System.nanoTime();
            System.out.println("Done writing to file");

            System.out.println("Setting tasks " + (afterTasks - before) + " Writing " + (afterWriting - afterTasks));

            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void decryptFileAsync(String inputFilePath, String outputFilePath, byte[][] subKeys) {
        System.out.println();
        System.out.println("Decrypting Async");

        try {
            byte [][] reversedSubKeys = reverseSubKeys(subKeys);
                    
            final File inputFile = new File(inputFilePath);
            final File outputFile = new File(outputFilePath);
            final InputStream inputStream = new BufferedInputStream(new FileInputStream(inputFile));
            final OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile));

            final List<Future<byte[]>> futures = new ArrayList<>();
            final ExecutorService executor = Executors.newFixedThreadPool(4);

            final long nbBytesFileWithoutHeader = inputFile.length() - 1;
            final long nbTotalBlocks = (long) Math.ceil(nbBytesFileWithoutHeader / (double) blockSizeInBytes);

            final int nbBytesPadding = inputStream.read();

            byte[] block = new byte[blockSizeInBytes];
            for (int nbBlocks = 1; nbBlocks <= nbTotalBlocks; nbBlocks++) {
                inputStream.read(block);

                final byte[] finalBlock = block;
                //System.out.println("Decrypting block async " + nbBlocks);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    return decryptBlock(finalBlock, reversedSubKeys);
                }, executor).exceptionally(ex -> {
                    throw (RuntimeException) ex;
                }));

                block = new byte[blockSizeInBytes];
            }
            inputStream.close();

            for (int i = 1; i <= nbTotalBlocks; i++) {
                byte[] decryptedBlock = futures.get(i-1).get();
                if  (i == nbTotalBlocks) {
                    decryptedBlock = Arrays.copyOfRange(decryptedBlock, 0, blockSizeInBytes - nbBytesPadding);
                }

                outputStream.write(decryptedBlock);
                //System.out.println("Blok " + i +" weggeschreven");
            }

            outputStream.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static byte[] encryptBlock(byte[] block, byte[][] subKeys) throws IllegalArgumentException {
        if (block.length != 8)
            throw new IllegalArgumentException("Block not 8 length");

        //long millis1 = System.nanoTime();
        final byte[] permutatedBlock = ByteHelper.permutFunc(block, initialPermutation);
        //long millis2 = System.nanoTime();
        //System.out.println("Time permutation " + (millis2 - millis1));

        byte[] prevLeft, prevRight, left, right;
        // verdeel in initiele linkse en rechtse blok
        prevLeft = Arrays.copyOfRange(permutatedBlock, 0, (int) Math.ceil(permutatedBlock.length / 2.0));
        prevRight = Arrays.copyOfRange(permutatedBlock, permutatedBlock.length / 2, permutatedBlock.length);

        // bereken L1 R1 tem L15 R15
        for (int i = 1; i <= 16; i++) {

            // bereken linkse en rechtse blok
            left = prevRight;

            //long millisBeforeXorFeistel = System.nanoTime();
            right = ByteHelper.xorByteBlocks(prevLeft, Feistel.executeFunction(prevRight, subKeys[i - 1]));
            //System.out.println("time xor feistel " + (System.nanoTime() - millisBeforeXorFeistel));

            // voorbereiding volgende iteratie
            prevLeft = left;
            prevRight = right;
        }

        // swap voor laatste iteratie
        left = prevRight;
        right = prevLeft;

        //long millis3 = System.nanoTime();
        //System.out.println("Time iterations " + (millis3 - millis2));

        return ByteHelper.permutFunc(ByteHelper.concatBlocks(left, right), inverseInitialPermutation);
    }


//    private static void encryptFile(String filePath, byte[][] subKeys) {
//        System.out.println();
//        System.out.println("Encrypting");
//
//        try {
//            final File inputFile = new File(filePath);
//            final File outputFile = new File(filePath + ".des");
//            final InputStream inputStream = new BufferedInputStream(new FileInputStream(inputFile));
//            final OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile));
//
//            final long nbBytesFile = inputFile.length();
//            final long nbTotalBlocks = (long) Math.ceil(nbBytesFile / (double) blockSizeInBytes);
//            final int nbBytesPaddingNeeded = (int) (blockSizeInBytes - (nbBytesFile % blockSizeInBytes));
//
//            final byte header = (byte) nbBytesPaddingNeeded;
//            outputStream.write(header);
//
//            byte[] block = new byte[blockSizeInBytes];
//            int bytesRead = 0;
//
//            for (long nbBlocks = 1; nbBlocks <= nbTotalBlocks; nbBlocks++) {
//
//                bytesRead = inputStream.read(block);
//
//                System.out.println("Encrypting block " + nbBlocks);
//                byte[] encryptedBlock = encryptBlock(block, subKeys);
//
//                // schrijf geencrypteerd blok weg naar output bestand
//                outputStream.write(encryptedBlock);
//
//                block = new byte[blockSizeInBytes];
//            }
//
//            inputStream.close();
//            outputStream.close();
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        } catch (IOException e) {
//            e.printStackTrace();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

//    private static void decryptFile(String filePath, byte[][] reversedSubKeys) {
//        System.out.println();
//        System.out.println("Decrypting");
//
//        try {
//            final File inputFile = new File(filePath);
//            final File outputFile = new File(filePath.replace(".des",""));
//            final InputStream inputStream = new BufferedInputStream(new FileInputStream(inputFile));
//            final OutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outputFile));
//
//            final long nbBytesFileWithoutHeader = inputFile.length() - 1;
//            final long nbTotalBlocks = (long) Math.ceil(nbBytesFileWithoutHeader / (double) blockSizeInBytes);
//            final int nbBytesHeading = inputStream.read();
//
//            byte[] block = new byte[blockSizeInBytes];
//            for (long nbBlocks = 1; nbBlocks <= nbTotalBlocks; nbBlocks++) {
//                inputStream.read(block);
//
//                System.out.println("Decrypting block " + nbBlocks);
//                byte[] decryptedBlock = decryptBlock(block, reversedSubKeys);
//
//                // schrijf geencrypteerd blok weg naar output bestand
//                // laatste blok => verwijder padding
//                if (nbBlocks == nbTotalBlocks) {
//                    byte[] blockWithoutPadding = Arrays.copyOfRange(decryptedBlock, 0, blockSizeInBytes - nbBytesHeading);
//                    outputStream.write(blockWithoutPadding);
//                } else {
//                    outputStream.write(decryptedBlock);
//                }
//
//                block = new byte[blockSizeInBytes];
//            }
//
//            inputStream.close();
//            outputStream.close();
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        } catch (IOException e) {
//            e.printStackTrace();
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//    }

    public static byte[] decryptBlock(byte[] block, byte[][] reversedSubKeys) throws IllegalArgumentException {
        return encryptBlock(block, reversedSubKeys);
    }

    // based on http://stackoverflow.com/a/17534234
    private static byte[][] reverseSubKeys(byte[][] subKeys) {
        byte[][] reversedSubKeys = new byte[subKeys.length][];
        for (int i = 0; i < subKeys.length; i++) {
            reversedSubKeys[i] = Arrays.copyOf(subKeys[subKeys.length - 1 - i], subKeys[subKeys.length - 1 - i].length);
        }
        return reversedSubKeys;
    }

 
}