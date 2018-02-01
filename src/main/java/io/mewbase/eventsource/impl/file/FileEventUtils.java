package io.mewbase.eventsource.impl.file;


import io.mewbase.bson.BsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.zip.CRC32;
import java.util.zip.Checksum;



public interface FileEventUtils {

    Logger logger = LoggerFactory.getLogger(FileEventUtils.class);

    static Path pathFromEventNumber(final long eventNumber) {
        return Paths.get(String.format("%016d", eventNumber));
    }

    static long eventNumberFromPath(final Path path) {
        return Long.parseLong(path.getFileName().toString());
    }

    /**
     * Look at all the files in the channel and return the event number of the next one
     * to be created.
     * Events are numbered starting from 1
     * @param channelPath
     * @return theNextValidEventNumber
     */
    static long nextEventNumberFromPath(final Path channelPath) throws IOException {
        return Files.list(channelPath)
                    .filter(f -> Files.isRegularFile(f))
                    .mapToLong(f -> Long.parseLong(f.toFile().getName()))
                    .max()
                    .orElse(0) + 1l;
    }


    /**
     * Given that a instant is in the past find the first event that is subsequent to that instant,
     * this may be the first event in the stream.
     * If the event in the future return the nextValid event number as above.
     * @param channelPath
     * @param startInstant
     * @return the first eventNumber subsequent to the point in time.
     * @throws IOException
     */
    static long eventNumberAfterInstant(final Path channelPath, final Instant startInstant) throws Exception {
        long startEpochMillis = startInstant.toEpochMilli();
        long startEpochSecs = startEpochMillis - 1001l;
        return Files.list(channelPath)
                .filter(f -> Files.isRegularFile(f)
                            && f.toFile().lastModified() > startEpochSecs
                            && FileEventUtils.fileToEpochMillis(f.toFile()) > startEpochMillis)
                .mapToLong(f -> Long.parseLong(f.toFile().getName()))
                .min()
                .orElse(nextEventNumberFromPath(channelPath));
    }


    /**
     * Ensure that a directory exists for a given channel name
     * and return a valid path for the channel
     */
    static Path ensureChannelExists(final Path baseDir, final String channelName) {
        final Path channelPath = baseDir.resolve(channelName);
        try {
            Files.createDirectories(channelPath);
        } catch (Exception exp) {
            logger.error("Error creating channel for subscription on channel "+channelName  ,exp);
        }
        return channelPath;
    }


    static ByteBuf eventToFile(final BsonObject event) {
        ByteBuf headedBuf = Unpooled.buffer();
        final byte [] bytes =  event.encode().getBytes();
        headedBuf.writeLong(Instant.now().toEpochMilli());
        headedBuf.writeLong(checksum(bytes));
        headedBuf.writeBytes(bytes);
        return headedBuf;
    }


    static FileEvent fileToEvent(File file) throws Exception {
        final long eventNumber = FileEventUtils.eventNumberFromPath(file.toPath());
        final ByteBuf headedBuf = Unpooled.wrappedBuffer(Files.readAllBytes(file.toPath()));
        final long epochMillis = headedBuf.readLong();
        final long crc32 = headedBuf.readLong();
        final ByteBuf eventBuf = Unpooled.buffer(headedBuf.readableBytes());
        headedBuf.readBytes(eventBuf);
        return new FileEvent(eventNumber,epochMillis,crc32,eventBuf);
        }


    static long checksum(byte [] evt) {
        Checksum crc = new CRC32();
        crc.update(evt,0, evt.length);
        return crc.getValue();
    }


    static long fileToEpochMillis(File file)  {
        try {
            final ByteBuf headedBuf = Unpooled.wrappedBuffer(Files.readAllBytes(file.toPath()));
            return headedBuf.readLong();
        } catch(Exception exp) {
            logger.error("Failed to read file timestamp for " + file.getName() );
            return 0l;
        }
    }

}