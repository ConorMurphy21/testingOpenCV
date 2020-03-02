import javax.sound.sampled.*;
import javax.swing.*;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.bytedeco.javacpp.indexer.UByteBufferIndexer;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.javacv.*;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.image.ImageView;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Controller {

	@FXML
	private ImageView imageView; // the image display window in the GUI


	private int width;
	private int height;
	private int sampleRate; // sampling frequency
	private int sampleSizeInBits;
	private int numberOfChannels;
	private double[] freq; // frequencies for each particular row
	private int numberOfQuantizionLevels;
	private int numberOfSamplesPerColumn;
	private Stage stage;
	private String videoFilename;

	private static final Java2DFrameConverter fxconverter = new Java2DFrameConverter(); //converts frames to java.awt.image
	private static final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

	@FXML
	private void initialize() {
		// Optional: You should modify the logic so that the user can change these values
		// You may also do some experiments with different values
		width = 64;
		height = 64;
		sampleRate = 8000;
		sampleSizeInBits = 8;
		numberOfChannels = 1;

		numberOfQuantizionLevels = 16;

		numberOfSamplesPerColumn = 500;

		// assign frequencies for each particular row
		freq = new double[height]; // Be sure you understand why it is height rather than width
		freq[height/2-1] = 440.0; // 440KHz - Sound of A (La)
		for (int m = height/2; m < height; m++) {
			freq[m] = freq[m-1] * Math.pow(2, 1.0/12.0);
		}
		for (int m = height/2-2; m >=0; m--) {
			freq[m] = freq[m+1] * Math.pow(2, -1.0/12.0);
		}
	}

	public void setStage(Stage stage){
		this.stage = stage;
	}

	private String getImageFilename() {
		// This method should return the filename of the image to be played
		// You should insert your code here to allow user to select the file
		FileChooser fileChooser = new FileChooser();
		File f = fileChooser.showOpenDialog(stage);
		return (f != null) ? f.getAbsolutePath() : null;
	}

	@FXML
	protected void openImage(ActionEvent event) throws InterruptedException {
		//todo: display the first frame of the image here
		videoFilename = getImageFilename();

	}


	private void playImage(Mat image) throws LineUnavailableException {
		//todo: replace image with frame

		// This method "plays" the image opened by the user
		// You should modify the logic so that it plays a video rather than an image
		// convert the image from RGB to grayscale
		Mat grayImage = new Mat();
		cvtColor(image, grayImage, COLOR_BGR2GRAY);

		// resize the image
		Mat resizedImage = new Mat();
		resize(grayImage,resizedImage,new Size(width,height));

		UByteRawIndexer imageIndexer = image.createIndexer();

		// quantization
		double[][] roundedImage = new double[resizedImage.rows()][resizedImage.cols()];
		for (int row = 0; row < resizedImage.rows(); row++) {
			for (int col = 0; col < resizedImage.cols(); col++) {
				roundedImage[row][col] = ((double)imageIndexer.get(row,col)/numberOfQuantizionLevels) / numberOfQuantizionLevels;
			}
		}

		// I used an AudioFormat object and a SourceDataLine object to perform audio output. Feel free to try other options
		AudioFormat audioFormat = new AudioFormat(sampleRate, sampleSizeInBits, numberOfChannels, true, true);
		SourceDataLine sourceDataLine = AudioSystem.getSourceDataLine(audioFormat);
		sourceDataLine.open(audioFormat, sampleRate);
		sourceDataLine.start();

		for (int col = 0; col < width; col++) {
			byte[] audioBuffer = new byte[numberOfSamplesPerColumn];
			for (int t = 1; t <= numberOfSamplesPerColumn; t++) {
				double signal = 0;
				for (int row = 0; row < height; row++) {
					int m = height - row - 1; // Be sure you understand why it is height rather width, and why we subtract 1
					int time = t + col * numberOfSamplesPerColumn;
					double ss = Math.sin(2 * Math.PI * freq[m] * (double)time/sampleRate);
					signal += roundedImage[row][col] * ss;
				}
				double normalizedSignal = signal / height; // signal: [-height, height];  normalizedSignal: [-1, 1]
				audioBuffer[t-1] = (byte) (normalizedSignal*0x7F); // Be sure you understand what the weird number 0x7F is for
			}
			sourceDataLine.write(audioBuffer, 0, numberOfSamplesPerColumn);
		}
		sourceDataLine.drain();
		sourceDataLine.close();
	}

	@FXML
	protected void playImage(ActionEvent event) {

		if(videoFilename == null){
			//todo: play error noise, display error
			return;
		}

		//todo: add special logic so that it can play images and video


		//todo: move these initialization steps to open Image, so it doesn't take as long to start playing
		Thread playThread = new Thread(() -> {
			try {
				//what we need to get video
				final FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFilename);
				grabber.start();

				int counter = 0;
				// This is so the main thread can interrupt this one
				while (!Thread.interrupted()) {
					//tried using grabKeyFrame, but there was only 1 keyFrame.
					Frame frame = grabber.grabImage();
					counter++;
					if (frame == null) {
						break;
					}
					if (frame.image != null) {
						//converts frame to swing image, then to javafx image
						final Image image = SwingFXUtils.toFXImage(fxconverter.convert(frame), null);
						Platform.runLater(() -> imageView.setImage(image)); // puts the frame as the imageview

						Mat mat = converter.convert(frame);
						if (counter % 30 == 0) {
							playImage(mat);
							play_click_sound();
						}
					}
				}
				grabber.stop(); // duh
				grabber.release(); // This is the stuff it prints
				Platform.exit(); // This is why it closes when it's done
			} catch (LineUnavailableException exception) {
				exception.printStackTrace();
				System.out.println("Something went wrong");
				//this most commonly occurs when the video is already in use
				//todo: add an error message and noise for this too
			} catch (FrameGrabber.Exception exception) {
				//I assume this is what occurs when the path is invalid
				exception.printStackTrace();
				System.out.println("Something else went wrong");
				//todo: add an error sound for this, because this means they didn't input a valid video
			}
		});
		playThread.start(); // start the thread we just made

	}

	public void play_click_sound(){
		new Thread(new Runnable() {

			String path = "click_sound.wav";
			public void run() {
				try {
					Clip clip = AudioSystem.getClip();
					AudioInputStream inputStream = AudioSystem.getAudioInputStream(
							Main.class.getResourceAsStream(path));
					clip.open(inputStream);
					clip.start();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}).start();
	}

}
