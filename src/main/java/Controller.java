import javax.sound.sampled.*;

import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.javacv.*;

import javafx.fxml.FXML;
import javafx.scene.image.ImageView;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Controller {

	@FXML
	public Text errorBox;
	@FXML
	public TextField samplePerColumnInput, sampleSizeInput, sampleRateInput,
			quantInput, heightInput, widthInput, nthframeInput;
	@FXML
	private ImageView imageView; // the image display window in the GUI


	private final IntegerProperty width = new SimpleIntegerProperty(64);
	private final IntegerProperty height = new SimpleIntegerProperty(64);
	private final IntegerProperty sampleRate = new SimpleIntegerProperty(8000);
	private final IntegerProperty sampleSizeInBits = new SimpleIntegerProperty(8);
	private final IntegerProperty numberOfQuantizationLevels = new SimpleIntegerProperty(16);
	private final IntegerProperty numberOfSamplesPerColumn = new SimpleIntegerProperty(500);
	private final IntegerProperty nthframe = new SimpleIntegerProperty(30);
	private final IntegerProperty[] props = {width, height, sampleRate, sampleSizeInBits,
			numberOfQuantizationLevels, numberOfSamplesPerColumn, nthframe};
	private static final HashMap<Integer, TextField> assocTextField = new HashMap<>();

	private double[] freq; // frequencies for each particular row
	private Stage stage;
	private String videoFilename;
	private static FFmpegFrameGrabber grabber;
	private static final Java2DFrameConverter fxconverter = new Java2DFrameConverter(); //converts frames to java.awt.image
	private static final OpenCVFrameConverter.ToMat converter = new OpenCVFrameConverter.ToMat();

	private static Thread playThread;

	private static Mat firstImage;

	@FXML
	private void initialize() {
		iniTextFieldHashMap();
		for(IntegerProperty p : props){
			TextField tf = assocTextField.get(p.hashCode());
			tf.setText(p.getValue().toString()); //set default text for inputs
			setIntegerListener(tf, p); //add listener to text input
		}

		int h = height.get();
		// assign frequencies for each particular row
		freq = new double[h]; // Be sure you understand why it is height rather than width
		freq[h/2-1] = 440.0; // 440KHz - Sound of A (La)
		for (int m = h/2; m < h; m++) {
			freq[m] = freq[m-1] * Math.pow(2, 1.0/12.0);
		}
		for (int m = h/2-2; m >=0; m--) {
			freq[m] = freq[m+1] * Math.pow(2, -1.0/12.0);
		}

	}

	private void iniTextFieldHashMap(){
		assocTextField.put(width.hashCode(), widthInput);
		assocTextField.put(height.hashCode(), heightInput);
		assocTextField.put(sampleRate.hashCode(), sampleRateInput);
		assocTextField.put(sampleSizeInBits.hashCode(), sampleSizeInput);
		assocTextField.put(numberOfQuantizationLevels.hashCode(), quantInput);
		assocTextField.put(numberOfSamplesPerColumn.hashCode(), samplePerColumnInput);
		assocTextField.put(nthframe.hashCode(),nthframeInput);
	}


	private void setIntegerListener(TextField t, IntegerProperty p){
		final int defaultValue = p.get();
		t.textProperty().addListener((obs,oldVal,newVal) -> {
			try {
				p.setValue(Integer.parseInt(newVal));
			}catch (NumberFormatException e) {
				if (newVal.isEmpty()) p.setValue(defaultValue);
				else t.setText(oldVal);
			}
		});
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
						errorBox.setText(e.getMessage());
					}
				}
			}else{
				if(playThread.isAlive()){
					playThread.interrupt();
				}
			}
		});
	}

	private String getImageFilename() {

		FileChooser fileChooser = new FileChooser();
		File f = fileChooser.showOpenDialog(stage);
		return (f != null) ? f.getAbsolutePath() : null;
	}

	@FXML
	protected void openImage() {
		// Do this so the error text goes away after they try something to fix it
		errorBox.setText("");
		if(playThread != null && playThread.isAlive()){
			playThread.interrupt();
		}
		videoFilename = getImageFilename();
		prepareVideoForPlaying();
	}

	private void prepareVideoForPlaying(){


		if(videoFilename == null){
			// no output because it's ok
			// no one clicks the exit button and expects to see a video magically appear.
			return;
		}

		try{
			if(grabber != null){
				grabber.stop();
				grabber.release();
			}

			grabber = new FFmpegFrameGrabber(videoFilename);
			grabber.start();
			Frame tn = grabber.grabImage();
			if (tn.image != null) {
				final Image image = SwingFXUtils.toFXImage(fxconverter.convert(tn), null);
				imageView.setImage(image); // puts the frame as the imageview
				firstImage = converter.convert(tn);
			}
			// cannot
		}catch (Exception e){
			playErrorSound();
			errorBox.setText(e.getMessage());
		}
	}

	@FXML
	protected void playFile() {
		//Do this so that after they try something new, the error text doesn't linger
		errorBox.setText("");

		if(videoFilename == null){
		    errorBox.setText("No File Found");
			playErrorSound();
			return;
		}
		if(playThread != null && playThread.isAlive()){
			return;
		}
		//todo: add special logic so that it can play images and video


		//todo: move these initialization steps to open Image, so it doesn't take as long to start playing
		playThread = new Thread(() -> {
			try {

				ExecutorService executor = Executors.newSingleThreadExecutor();

				if(firstImage != null){
					playImage(firstImage, executor);
				}

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
						if (counter % nthframe.get() == 0) {
							playClickSound();
							playImage(mat, executor);
						}
					}
				}
				executor.shutdownNow();
				executor.awaitTermination(10, TimeUnit.SECONDS);
				grabber.stop();
				grabber.release();
				if(!Thread.interrupted()){
					//prepare to play the same video again if we didn't exit because of an interupt
					prepareVideoForPlaying();
				}
			} catch (FrameGrabber.Exception | InterruptedException | LineUnavailableException e) {
				e.printStackTrace();

				errorBox.setText(e.getMessage());
				playErrorSound();
			} //I assume this is what occurs when the path is invalid

		});
		playThread.start(); // start the thread we just made

	}


	private void playImage(Mat image, ExecutorService executor) throws LineUnavailableException {
		//todo: replace image with frame

		// This method "plays" the image opened by the user
		// You should modify the logic so that it plays a video rather than an image
		// convert the image from RGB to grayscale
		Mat grayImage = new Mat();
		cvtColor(image, grayImage, COLOR_BGR2GRAY);

		// resize the image
		Mat resizedImage = new Mat();
		int w = width.get(), h = height.get();
		resize(grayImage,resizedImage,new Size(w, h));

		UByteRawIndexer imageIndexer = image.createIndexer();

		// quantization
		double[][] roundedImage = new double[resizedImage.rows()][resizedImage.cols()];
		for (int row = 0; row < resizedImage.rows(); row++) {
			for (int col = 0; col < resizedImage.cols(); col++) {

										//This function didn't properly normalize when number NOQL was not 16
				//roundedImage[row][col] = (double)Math.floor(imageIndexer.get(row,col)/numberOfQuantizionLevels) / numberOfQuantizionLevels;
									//example: 255/8 = 31, 31/8 = 3.875. this is not between 0 and 1
									//it only worked because 256/16/16 = 1, or because 256/16 = 16

				int nql = numberOfQuantizationLevels.get();
				roundedImage[row][col] = Math.floor(imageIndexer.get(row,col)/(256.0/nql)) / nql;
				//same example: 255/(256/8) = 7, 7/8 < 1
			}
		}
		AudioFormat audioFormat = new AudioFormat(sampleRate.get(), sampleSizeInBits.get(),
				1, true, true);
		SourceDataLine sourceDataLine = AudioSystem.getSourceDataLine(audioFormat);
		sourceDataLine.open(audioFormat, sampleRate.get());
		sourceDataLine.start();

		// I used an AudioFormat object and a SourceDataLine object to perform audio output. Feel free to try other options

		int spc = numberOfSamplesPerColumn.get(), sr = sampleRate.get();

		for (int col = 0; col < w; col++) {
			byte[] audioBuffer = new byte[spc];
			for (int t = 1; t <= spc; t++) {
				double signal = 0;
				for (int row = 0; row < h; row++) {
					int m = h - row - 1; // Be sure you understand why it is height rather width, and why we subtract 1
					int time = t + col * spc;
					double ss = Math.sin(2 * Math.PI * freq[m] * (double)time/sr);
					signal += roundedImage[row][col] * ss;
				}
				double normalizedSignal = signal / h; // signal: [-height, height];  normalizedSignal: [-1, 1]
				audioBuffer[t-1] = (byte) (normalizedSignal*0x7F); // Be sure you understand what the weird number 0x7F is for
			}
			try {
				executor.submit(() -> {
					sourceDataLine.write(audioBuffer, 0, spc);
				}).get();
			} catch (InterruptedException | ExecutionException interruptedException) {
				Thread.currentThread().interrupt();
			}
		}
		try {
			executor.submit(sourceDataLine::drain).get();
		} catch (InterruptedException | ExecutionException interruptedException) {
			Thread.currentThread().interrupt();
		}
		sourceDataLine.stop();
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
				errorBox.setText(e.getMessage());

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
