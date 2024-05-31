package com.example.live_stream_2;

import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.bytedeco.javacv.OpenCVFrameRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@RestController
public class RecordingController {

    private static final Logger logger = LoggerFactory.getLogger(RecordingController.class);

    private final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean recording = false;
    private long startTime;
    private String outputFile = "output.mp4";
    private OpenCVFrameGrabber grabber;
    private OpenCVFrameRecorder recorder;

    @PostMapping("/start-recording")
    public ResponseEntity<?> startRecording() {
        if (recording) {
            logger.warn("Recording is already in progress.");
            return ResponseEntity.status(409).body("{\"status\": \"already recording\"}");
        }

        executorService.submit(() -> {
            recording = true;
            try {
                grabber = new OpenCVFrameGrabber(0);
                recorder = new OpenCVFrameRecorder(outputFile, 640, 480);

                logger.info("Starting grabber...");
                grabber.start();

                logger.info("Starting recorder...");
                recorder.start();

                logger.info("Recording...");
                startTime = System.currentTimeMillis();
                while (recording) {
                    recorder.record(grabber.grab());
                }

                stopRecorderAndGrabber();

            } catch (Exception e) {
                logger.error("Error during recording: ", e);
                stopRecorderAndGrabber();
            }
        });

        return ResponseEntity.ok("{\"status\": \"success\"}");
    }

    @PostMapping("/stop-recording")
    public ResponseEntity<?> stopRecording() {
        if (!recording) {
            logger.warn("No recording in progress.");
            return ResponseEntity.status(409).body("{\"status\": \"not recording\"}");
        }

        recording = false;
        stopRecorderAndGrabber();

        return ResponseEntity.ok("{\"status\": \"success\"}");
    }

    @PostMapping("/trim-video")
    public ResponseEntity<?> trimVideo() {
        if (!recording) {
            logger.warn("No recording in progress.");
            return ResponseEntity.status(409).body("{\"status\": \"not recording\"}");
        }

        // Stop the recording
        recording = false;
        stopRecorderAndGrabber();

        // Ensure that the trimmed_videos folder exists
        createTrimmedVideosFolder();

        executorService.submit(() -> {
            try {
                String tempCopyPath = "output_temp_" + System.currentTimeMillis() + ".mp4";
                String trimmedFilePath = "trimmed_videos/trimmed_output_" + System.currentTimeMillis() + ".mp4";

                // Copy the current recording file
                Files.copy(Paths.get(outputFile), Paths.get(tempCopyPath));

                // Get the duration of the video using ffprobe
                String[] cmd = {"ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", tempCopyPath};
                Process process = new ProcessBuilder(cmd).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String durationStr = reader.readLine();
                process.waitFor();
                double duration = Double.parseDouble(durationStr);

                // Calculate the start time (2 seconds before the end)
                double startTime = Math.max(0, duration - 8);

                // Trim the video
                boolean success = trimVideoSegment(tempCopyPath, startTime, 8, trimmedFilePath);

                if (success) {
                    logger.info("Video successfully trimmed and saved as {}", trimmedFilePath);
                    Files.delete(Paths.get(tempCopyPath)); // Optionally delete the temporary copy
                } else {
                    logger.error("Video trimming failed.");
                }

                // Wait for 1 second before resuming recording
                TimeUnit.SECONDS.sleep(1);

                // Resume recording
                startRecording();

            } catch (Exception e) {
                logger.error("Error during video trimming: ", e);
            }
        });

        return ResponseEntity.ok("{\"status\": \"success\"}");
    }

    private boolean trimVideoSegment(String inputFilePath, double startTime, double duration, String outputFilePath) {
        // Construct the FFmpeg command to trim the video
        String[] command = {
            "ffmpeg",
            "-i", inputFilePath,
            "-ss", String.valueOf(startTime),
            "-t", String.valueOf(duration),
            "-c", "copy",
            outputFilePath
        };

        Process process = null;
        try {
            // Execute the FFmpeg command
            process = new ProcessBuilder(command).start();

            // Capture the output and error streams for debugging
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            String s;
            while ((s = stdInput.readLine()) != null) {
                logger.info(s);
            }
            while ((s = stdError.readLine()) != null) {
                logger.error(s);
            }

            // Wait for the process to complete
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("Trimmed video saved as {}", outputFilePath);
                return true;
            } else {
                logger.error("FFmpeg process failed with exit code {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            logger.error("Error during video trimming: ", e);
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private void stopRecorderAndGrabber() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
                recorder = null;
                logger.info("Recorder stopped.");
            }
            if (grabber != null) {
                grabber.stop();
                grabber.release();
                grabber = null;
                logger.info("Grabber stopped.");
            }
        } catch (Exception e) {
           
            logger.error("Error stopping recorder or grabber: ", e);
        }
    }
    
    @PreDestroy
    public void cleanup() {
        executorService.shutdown();
        stopRecorderAndGrabber();
    }
    
    private void createTrimmedVideosFolder() {
        Path folderPath = Paths.get("trimmed_videos");
        if (!Files.exists(folderPath)) {
            try {
                Files.createDirectories(folderPath);
            } catch (IOException e) {
                logger.error("Error creating trimmed_videos folder: ", e);
            }
        }
    }
}


//////////////////////////////Case 2 : While click on trim button recording will stopped and trim Recording is saved trimmed_videos folder before 8 second of click duration & Recording will start automatically again 30-05-24 (7:00 PM) //////////////////////////////