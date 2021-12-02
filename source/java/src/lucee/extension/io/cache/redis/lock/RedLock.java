package lucee.extension.io.cache.redis.lock;

import lucee.extension.io.cache.redis.udf.RedisCommand;
import lucee.extension.io.cache.redis.udf.RedisCommandLowPriority;
import lucee.extension.io.cache.util.print;
import lucee.loader.engine.CFMLEngine;
import lucee.loader.engine.CFMLEngineFactory;
import lucee.loader.util.Util;
import lucee.runtime.PageContext;
import lucee.runtime.exp.PageException;
import lucee.runtime.type.Array;

public class RedLock {

	private CFMLEngine engine;

	private String cacheName;

	private String name;

	private long timeout;

	private boolean throwontimeout;

	private boolean logontimeout;

	private int expires;

	private int amount;

	private boolean release;

	private String lockNamePrefix;

	private String lockNameOpen;

	private String lockNameClose;

	public RedLock(String name, String cacheName, int amount, long timeout, boolean throwontimeout, boolean logontimeout, int expires) throws PageException {
		engine = CFMLEngineFactory.getInstance();

		if (Util.isEmpty(name, true)) throw engine.getExceptionUtil().createApplicationException("name is required and cannot be empty!");
		this.name = name.trim();

		if (Util.isEmpty(cacheName, true)) throw engine.getExceptionUtil().createApplicationException("cache is required and cannot be empty!");
		this.cacheName = cacheName.trim();

		if (timeout < 10L) throw engine.getExceptionUtil().createApplicationException("timeout must be at least 0.01 seconds (10ms)");
		else this.timeout = timeout;
		this.throwontimeout = throwontimeout;
		this.logontimeout = logontimeout;

		this.expires = expires;
		if (this.expires <= 0) this.expires = 600;

		this.amount = amount;
		if (this.amount < 1) throw engine.getExceptionUtil().createApplicationException("amount need to be at least 1, now it is " + amount);

		this.release = false;

		this.lockNamePrefix = "dilo:" + this.name + ":";
		this.lockNameOpen = this.lockNamePrefix + "open";
		this.lockNameClose = this.lockNamePrefix + "close";
	}

	/**
	 * Try to acquire a lock for this resource
	 * 
	 * @param resource Resource
	 * @return LockResult containing resource, value, validityTime if lock is acquired successfully or
	 *         else null
	 * @throws PageException
	 */
	public boolean lock(PageContext pc) throws PageException {
		release = false;

		Array commands = engine.getCreationUtil().createArray();

		Array cmd1 = engine.getCreationUtil().createArray();
		cmd1.append("eval");
		cmd1.append("local open_len = redis.call('llen', KEYS[1]);"

				+ " local close_len = redis.call('llen', ARGV[1]);"

				+ " local time=redis.call('time')[1];"

				+ " if open_len	+ close_len < " + amount + " then redis.call('LPUSH', KEYS[1], time)"

				+ " elseif open_len + close_len > " + amount + " then redis.call('DEL', KEYS[1]) end");
		cmd1.append("1");
		cmd1.append(lockNameOpen);
		cmd1.append(lockNameClose);
		commands.append(cmd1);

		Array cmd2 = engine.getCreationUtil().createArray();
		cmd2.append("BRPOPLPUSH");
		cmd2.append(lockNameOpen);
		cmd2.append(lockNameClose);
		cmd2.append(timeoutInSeconds());
		commands.append(cmd2);

		Array cmd3 = engine.getCreationUtil().createArray();
		cmd3.append("expire");
		cmd3.append(lockNameClose);
		cmd3.append(expires + "");
		commands.append(cmd3);

		Array res = engine.getCastUtil().toArray(new RedisCommandLowPriority().invoke(pc, engine, commands, false, null, cacheName), null);
		// we could NOT aquire a lock
		if (res == null || !res.containsKey(2)) {
			print.e(res);
			// in case of a null, the expire in the last RedisCommand prolong the list expiry with no reason.
			// this code revert back to the expire it should be by looking on the current time and subtract the
			// last lock time
			Array cmd = engine.getCreationUtil().createArray();
			cmd.append("eval");
			cmd.append("local ltime=redis.call('lrange',KEYS[1],0,0);"

					+ " if ltime[1] ~= nil then "

					+ " local lock_remain_time = ARGV[1] - (redis.call('time')[1] - ltime[1]);"

					+ " if lock_remain_time > 0 then"

					+ " redis.call('expire',KEYS[1],lock_remain_time);"

					+ " end end");
			cmd.append("1");
			cmd.append(lockNameClose);
			cmd.append(expires + "");

			new RedisCommand().invoke(pc, engine, cmd, false, null, cacheName);
			if (logontimeout) {
				pc.getConfig().getLog("application").error("RedLock", "reached timeout [" + timeoutInSeconds() + "] for log [" + name + "]");
			}
			if (throwontimeout) {
				throw engine.getExceptionUtil().createApplicationException("we could not aquire a log for the name [" + name + "] in [" + timeoutInSeconds() + "] seconds.");
			}
			return false; // skip body
		}
		release = true;
		return true;
	}

	String timeoutInSeconds() {
		return engine.getCastUtil().toString((((int) (timeout / 10L)) / 100d));
	}

	/**
	 * Release the lock for this resource from all instances
	 * 
	 * @param resource Resource
	 * @param value Value
	 * @throws PageException
	 */
	public void release(PageContext pc) throws PageException {
		if (release) {
			// slide to unlock
			Array cmd = engine.getCreationUtil().createArray();
			cmd.append("RPOPLPUSH");
			cmd.append(lockNameClose);
			cmd.append(lockNameOpen);

			Object res = new RedisCommand().invoke(pc, engine, cmd, false, null, cacheName);

			if (res == null) {
				pc.getConfig().getLog("application").info("RedLock", "could not release the lock [" + name + "], lock is not present");
			}
		}
	}
}