import javax.sound.sampled.*;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.javacv.*;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.image.ImageView;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import java.io.File;

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
	private static FFmpegFrameGrabber grabber;
	private static final Java2DFrameConverter fxconverter = new Java2DFrameConverter(); //converts frames to java.awt.image
	private static final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

	private static Thread playThread;

	@FXML
	private void initialize() {
		// Optional: You should modify the logic so that the user can change these values
		// You may also do some experiments with different values
		width = 64;
		height = 64;
		sampleRate = 8000;
		sampleSizeInBits = 8;
		numberOfChannels = 1;

		numberOfQuantizionLevels = 8;

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

		//Make sure we clean up our resources when we are done
		stage.setOnCloseRequest(we -> {
			if(playThread == null){
				if(grabber != null){
					try {
						grabber.stop();
						grabber.release();
					} catch (FrameGrabber.Exception e) {
						e.printStackTrace();
					}
				}
			}else{
				if(playThread.isAlive()){
					playThread.interrupt();
				}
			}
			System.exit(0);
		});
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

		videoFilename = getImageFilename();

		if(videoFilename == null){
		    // no output because it's ok
			// no one clicks the exit button and expects to see a video magically appear.
			return;
		}

		try{
			grabber = new FFmpegFrameGrabber(videoFilename);
			grabber.start();
			Frame tn = grabber.grabImage();
			if (tn.image != null) {
				final Image image = SwingFXUtils.toFXImage(fxconverter.convert(tn), null);
				imageView.setImage(image); // puts the frame as the imageview
			}
		}catch (Exception e){
			playErrorSound();
			System.out.println("Could not display thumbnail.");
		}


	}

	@FXML
	protected void playFile(ActionEvent event) {

		if(videoFilename == null){
			System.out.println("File not found.");
			playErrorSound();
			return;
		}
		//todo: add special logic so that it can play images and video


		//todo: move these initialization steps to open Image, so it doesn't take as long to start playing
		playThread = new Thread(() -> {
			try {

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
							playClickSound();
						}
					}
				}
				grabber.stop(); // duh
				grabber.release(); // This is the stuff it prints
			} catch (LineUnavailableException exception) {
				exception.printStackTrace();
				System.out.println("Something went wrong");
				//this most commonly occurs when the video is already in use
				System.out.println("Please wait for this video to end.");
				playErrorSound();
			} catch (FrameGrabber.Exception exception) {
				//I assume this is what occurs when the path is invalid
				exception.printStackTrace();
				System.out.println("Something else went wrong");
				playErrorSound();
			}
		});
		playThread.start(); // start the thread we just made

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

										//This function didn't properly normalize when number NOQL was not 16
				//roundedImage[row][col] = (double)Math.floor(imageIndexer.get(row,col)/numberOfQuantizionLevels) / numberOfQuantizionLevels;
									//example: 255/8 = 31, 31/8 = 3.875. this is not between 0 and 1
									//it only worked because 256/16/16 = 1, or because 256/16 = 16

				roundedImage[row][col] = Math.floor(imageIndexer.get(row,col)/(256.0/numberOfQuantizionLevels)) / numberOfQuantizionLevels;
				//same example: 255/(256/8) = 7, 7/8 < 1
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

	/***PLAY USER FEEDBACK NOISES ***/
	public void playSound(String filename){
		new Thread(() -> {
			try {
				Clip clip = AudioSystem.getClip();
				AudioInputStream inputStream = AudioSystem.getAudioInputStream(
						Main.class.getResourceAsStream(filename));
				clip.open(inputStream);
				clip.start();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}).start();
	}

	public void playClickSound(){
		playSound("click_sound.wav");
	}

	public void playErrorSound(){

		playSound("error.wav");
	}

}
