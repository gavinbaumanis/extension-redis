package lucee.extension.io.cache.redis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import lucee.extension.io.cache.util.Coder;
import lucee.runtime.exp.PageException;

/**
 * A lightweight implementation of the Redis server protocol at https://redis.io/topics/protocol
 * <p>
 * Effectively a complete Redis client implementation.
 */
public class Redis {
	/**
	 * Implements the encoding (writing) side.
	 */
	static class Encoder {
		/**
		 * CRLF is used a lot.
		 */
		private static byte[] CRLF = new byte[] { '\r', '\n' };

		/**
		 * This stream we will write to.
		 */
		private final OutputStream out;

		/**
		 * Construct the encoder with the passed outputstream the encoder will write to.
		 *
		 * @param out Will be used to write all encoded data to.
		 */
		Encoder(OutputStream out) {
			this.out = out;
		}

		/**
		 * Write a byte array in the "RESP Bulk String" format.
		 *
		 * @param value The byte array to write.
		 * @throws IOException Propagated from the output stream.
		 * @link https://redis.io/topics/protocol#resp-bulk-strings
		 */
		void write(byte[] value) throws IOException {
			out.write('$');
			out.write(Long.toString(value.length).getBytes());
			out.write(CRLF);
			out.write(value);
			out.write(CRLF);
		}

		/**
		 * Write a long value in the "RESP Integers" format.
		 *
		 * @param val The value to write.
		 * @throws IOException Propagated from the output stream.
		 * @link https://redis.io/topics/protocol#resp-integers
		 */
		void write(long val) throws IOException {
			out.write(':');
			out.write(Long.toString(val).getBytes());
			out.write(CRLF);
		}

		/**
		 * Write a list of objects in the "RESP Arrays" format.
		 *
		 * @param list A list of objects that contains Strings, Longs, Integers and (recursively) Lists.
		 * @throws IOException Propagated from the output stream.
		 * @throws IllegalArgumentException If the list contains unencodable objects.
		 * @link https://redis.io/topics/protocol#resp-arrays
		 */
		void write(Object[] arr) throws IOException, IllegalArgumentException {
			out.write('*');
			out.write(Long.toString(arr.length).getBytes());
			out.write(CRLF);

			for (Object o: arr) {
				if (o instanceof byte[]) {
					write((byte[]) o);
				}
				else if (o instanceof byte[][]) {
					for (byte[] barr: (byte[][]) o) {
						write(barr);
					}
				}
				else if (o instanceof String) {
					// write((Serializable) o);
					write(((String) o).getBytes(Coder.UTF8));
				}
				else if (o instanceof Long) {
					write((Long) o);
				}
				else if (o instanceof Integer) {
					write(((Integer) o).longValue());
				}
				else {
					throw new IllegalArgumentException("Unexpected type " + o.getClass().getCanonicalName());
				}
			}
		}

		void flush() throws IOException {
			out.flush();
		}
	}

	/**
	 * Implements the parser (reader) side of protocol.
	 */
	static class Parser {
		/**
		 * Thrown whenever data could not be parsed.
		 */
		static class ProtocolException extends IOException {
			ProtocolException(String msg) {
				super(msg);
			}
		}

		/**
		 * Thrown whenever an error string is decoded.
		 */
		static class ServerError extends IOException {
			ServerError(String msg) {
				super(msg);
			}
		}

		/**
		 * The input stream used to read the data from.
		 */
		private final InputStream input;
		private final ClassLoader cl;

		/**
		 * Constructor.
		 *
		 * @param input The stream to read the data from.
		 */
		Parser(ClassLoader cl, InputStream input) {
			this.cl = cl;
			this.input = input;
		}

		/**
		 * Parse incoming data from the stream.
		 * <p>
		 * Based on each of the markers which will identify the type of data being sent, the parsing is
		 * delegated to the type-specific methods.
		 *
		 * @return The parsed object
		 * @throws IOException Propagated from the stream
		 * @throws ProtocolException In case unexpected bytes are encountered.
		 */
		Object parse() throws IOException, ProtocolException {
			Object ret;
			int read = this.input.read();
			switch (read) {
			case '+':
				ret = this.parseSimpleString();
				break;
			case '-':
				throw new ServerError(new String(this.parseSimpleString()));
			case ':':
				ret = this.parseNumber();
				break;
			case '$':
				ret = this.parseBulkString();
				break;
			case '*':
				long len = this.parseNumber();
				if (len == -1) {
					ret = null;
				}
				else {
					List<Object> arr = new LinkedList<>();
					for (long i = 0; i < len; i++) {
						arr.add(this.parse());
					}
					ret = arr;
				}
				break;
			case -1:
				return null;
			default:
				throw new ProtocolException("Unexpected input: " + (byte) read);
			}

			return ret;
		}

		/**
		 * Parse "RESP Bulk string" as a String object.
		 *
		 * @return The parsed response
		 * @throws IOException Propagated from underlying stream.
		 */
		private byte[] parseBulkString() throws IOException, ProtocolException {
			final long expectedLength = parseNumber();
			if (expectedLength == -1) {
				return null;
			}
			if (expectedLength > Integer.MAX_VALUE) {
				throw new ProtocolException("Unsupported value length for bulk string");
			}
			final int numBytes = (int) expectedLength;
			final byte[] buffer = new byte[numBytes];
			int read = 0;
			while (read < expectedLength) {
				read += input.read(buffer, read, numBytes - read);
			}
			if (input.read() != '\r') {
				throw new ProtocolException("Expected CR");
			}
			if (input.read() != '\n') {
				throw new ProtocolException("Expected LF");
			}
			return buffer;
		}

		/**
		 * Parse "RESP Simple String"
		 *
		 * @return Resultant string
		 * @throws IOException Propagated from underlying stream.
		 */
		private byte[] parseSimpleString() throws IOException {
			return scanCr(1024);
		}

		private long parseNumber() throws IOException {
			return Long.valueOf(new String(scanCr(1024)));
		}

		private byte[] scanCr(int size) throws IOException {
			int idx = 0;
			int ch;
			byte[] buffer = new byte[size];
			while ((ch = input.read()) != '\r') {
				buffer[idx++] = (byte) ch;
				if (idx == size) {
					// increase buffer size.
					size *= 2;
					buffer = java.util.Arrays.copyOf(buffer, size);
				}
			}
			if (input.read() != '\n') {
				throw new ProtocolException("Expected LF");
			}

			return Arrays.copyOfRange(buffer, 0, idx);
		}
	}

	/**
	 * Used for writing the data to the server.
	 */
	private final Encoder writer;

	/**
	 * Used for reading responses from the server.
	 */
	private final Parser reader;

	private final Socket socket;

	protected final ClassLoader cl;

	public final long created;

	public long lastUsed;

	/**
	 * Construct the connection with the specified Socket as the server connection with default buffer
	 * sizes.
	 *
	 * @param socket Connected socket to the server.
	 * @throws IOException If a socket error occurs.
	 */
	public Redis(ClassLoader cl, Socket socket) throws IOException {
		this(cl, socket, 1 << 16, 1 << 16);
	}

	/**
	 * Construct the connection with the specified Socket as the server connection with specified buffer
	 * sizes.
	 *
	 * @param socket Socket to connect to
	 * @param inputBufferSize buffer size in bytes for the input stream
	 * @param outputBufferSize buffer size in bytes for the output stream
	 * @throws IOException If a socket error occurs.
	 */
	public Redis(ClassLoader cl, Socket socket, int inputBufferSize, int outputBufferSize) throws IOException {
		this.cl = cl;
		this.socket = socket;
		this.reader = new Parser(cl, new BufferedInputStream(socket.getInputStream(), inputBufferSize));
		this.writer = new Encoder(new BufferedOutputStream(socket.getOutputStream(), outputBufferSize));
		this.lastUsed = this.created = System.currentTimeMillis();
	}

	public Socket getSocket() {
		return socket;
	}

	/**
	 * Execute a Redis command and return it's result.
	 *
	 * @param args Command and arguments to pass into redis.
	 * @return Result of redis.
	 * @throws IOException All protocol and io errors are IO exceptions.
	 * @throws PageException
	 */
	public Object call(Object... args) throws IOException {

		// has keys?
		boolean hasKeys = false;
		for (Object o: args) {
			if (o instanceof byte[][] || o instanceof List) {
				hasKeys = true;
				break;
			}
		}
		if (hasKeys) {
			List<Object> list = new ArrayList<>();
			for (Object o: args) {
				if (o instanceof byte[][]) {
					for (byte[] barr: (byte[][]) o) {
						list.add(barr);
					}
				}
				else if (o instanceof List) {
					for (byte[] barr: (List<byte[]>) o) {
						list.add(barr);
					}
				}
				else list.add(o);
			}
			args = list.toArray();
		}

		writer.write(args);
		writer.flush();
		return read();
	}

	/**
	 * Does a blocking read to wait for redis to send data.
	 */
	public Object read() throws IOException {
		return reader.parse();
	}

	/**
	 * Helper class for pipelining.
	 */
	public static abstract class Pipeline {
		/**
		 * Write a new command to the server.
		 *
		 * @param args Command and arguments.
		 * @return self for chaining
		 * @throws IOException Propagated from underlying server.
		 */
		public abstract Pipeline call(Object... args) throws IOException;

		/**
		 * Returns an aligned list of responses for each of the calls.
		 *
		 * @return The responses
		 * @throws IOException Propagated from underlying server.
		 */
		public abstract List read() throws IOException;

	}

	/**
	 * Create a pipeline which writes all commands to the server and only starts reading the response
	 * when read() is called.
	 *
	 * @return A pipeline object.
	 */
	public Pipeline pipeline() {
		return new Pipeline() {
			private int n = 0;

			@Override
			public Pipeline call(Object... args) throws IOException {
				writer.write(args);
				writer.flush();
				n++;
				return this;
			}

			@Override
			public List<Object> read() throws IOException {
				List<Object> ret = new LinkedList<>();
				while (n-- > 0) {
					ret.add(reader.parse());
				}
				return ret;
			}
		};
	}

	@FunctionalInterface
	public interface FailableConsumer<T, E extends Throwable> {
		void accept(T t) throws E;
	}

	/**
	 * Utility method to execute some command with redis and close the connection directly after.
	 *
	 * @param callback The callback to perform with redis.
	 * @param addr Connection IP address
	 * @param port Connection port
	 * @throws IOException Propagated
	 */

	public static void run(ClassLoader cl, FailableConsumer<Redis, IOException> callback, Socket s) throws IOException {
		callback.accept(new Redis(cl, s));
	}
}