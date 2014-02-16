/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007-2014 InstantCom Ltd. All rights reserved.
 *
 */

package net.instantcom.util;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Date;
import java.util.Random;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.support.WebArgumentResolver;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

/**
 * <p>
 * HTTP Cache-Control/ETag/Expires/Vary header implementation for spring webmvc.
 * Pass it as an argument to controller method. Use fluent API calls to set
 * Cache-Control specific and call validate() to check against HTTP request. If
 * nothing has changed, CacheControl::validate() will throw {@link NotModified}
 * runtime exception which gets translated into 304 Not Modified HTTP response.
 * Code after validate call is shortcuted including any view rendering.
 * </p>
 * 
 * <p>
 * ETag is calculated as an MD5 checksum of hash codes of passed objects. If
 * object need a finer control over what will be used for etag calculation, it
 * can implement {@link CacheControl.Aware} inteface and explicity call
 * CacheControl::etag() with parameters that vary.
 * </p>
 * 
 * <p>
 * In addition to ETag and Cache-Control headers, you can also set
 * Last-Modified, and Vary headers. Expires and Pragma: no-cache will be
 * inferred if not explicitly set.
 * </p>
 * 
 * @author Vjekoslav Nesek (vnesek@instantcom.net)
 * 
 */
public class CacheControl {

	/**
	 * {@link ArgumentResolver} for {@link CacheControl} instances. You need to
	 * register it in spring's webmvc configuration, i.e. in
	 * {@link WebMvcConfigurerAdapter} addArgumentResolvers() method.
	 */
	public static class ArgumentResolver implements WebArgumentResolver {

		@Override
		public Object resolveArgument(MethodParameter methodParameter, NativeWebRequest webRequest) throws Exception {
			Object arg;
			if (CacheControl.class.equals(methodParameter.getParameterType())) {
				arg = new CacheControl(webRequest);
			} else {
				arg = UNRESOLVED;
			}
			return arg;
		}
	}

	/**
	 * Implement on objects that need fine grained cache control. When
	 * implementors are passed to CacheControl::deep() call, deepCheck() is
	 * called on implementor. You can than pass parameters for etag generation,
	 * set lastModified and such.
	 */
	public interface Aware {

		void deepCheck(CacheControl cc);
	}

	/**
	 * Exception throwed from validate() call to shortcut controller method.
	 * Renders 304 Not Modified.
	 * 
	 */
	@ResponseStatus(value = HttpStatus.NOT_MODIFIED)
	public static class NotModified extends Exception {
		private static final long serialVersionUID = 304L;
	}

	private enum Header {
		CACHE_CONTROL, ETAG("ETag"), EXPIRES, IF_MODIFIED_SINCE, IF_NONE_MATCH, LAST_MODIFIED, PRAGMA, VARY;

		private static String headerCase(String s) {
			int m = s.length();
			StringBuilder b = new StringBuilder(m);
			boolean upper = true;
			for (int i = 0; i < m; ++i) {
				char c = s.charAt(i);
				if (c == '_') {
					b.append('-');
					upper = true;
				} else {
					b.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
					upper = false;
				}
			}
			return b.toString();
		}

		Header() {
			this.representation = headerCase(this.name());
		}

		Header(String rep) {
			this.representation = rep;
		}

		@Override
		public String toString() {
			return representation;
		}

		private final String representation;
	}

	/**
	 * Seed value for ETag calculation. Random generated upon each app restart.
	 * Set it by using net.instantcom.util.CacheControl.seed system property or
	 * by calling setSeed method.
	 */
	private static long seed;

	static {
		try {
			seed = Long.parseLong(System.getProperty("net.instantcom.util.CacheControl.seed", null));
		} catch (Exception e) {
			seed = new Random().nextLong();
		}
	}

	public static void setSeed(long s) {
		seed = s;
	}

	private static final String strip(String str, String toStrip) {
		int i = 0;
		int j = str.length() - 1;
		// String start of str
		while (i <= j && toStrip.indexOf(str.charAt(i)) != -1) {
			++i;
		}
		// String end of str
		while (i <= j && toStrip.indexOf(str.charAt(j)) != -1) {
			--j;
		}
		return str.substring(i, j + 1);
	}

	public CacheControl(HttpServletRequest request, HttpServletResponse response) {
		this.request = request;
		this.response = response;
		this.etagCache = ByteBuffer.allocate(64);

		{ // Check for Cache-Control: no-cache
			String header = request.getHeader(Header.CACHE_CONTROL.toString());
			if (header != null && header.indexOf("no-cache") != -1) {
				disableNotModified = true;
			}
		}
		{ // Check for Pragma: no-cache
			String header = request.getHeader(Header.PRAGMA.toString());
			if (header != null && header.indexOf("no-cache") != -1) {
				disableNotModified = true;
			}
		}

		// etag seed
		etag(seed);
	}

	public CacheControl(NativeWebRequest nvr) throws NoSuchAlgorithmException {
		this((HttpServletRequest) nvr.getNativeRequest(), (HttpServletResponse) nvr.getNativeResponse());
	}

	public CacheControl cachePrivate() {
		if (!validated) {
			this.cachePrivate = true;
		}
		return this;
	}

	public CacheControl cachePublic() {
		if (!validated) {
			this.cachePublic = true;
		}
		return this;
	}

	public CacheControl deep(CacheControl.Aware... ccas) {
		if (!validated) {
			if (ccas != null) {
				for (CacheControl.Aware cca : ccas) {
					if (cca != null) {
						cca.deepCheck(this);
					}
				}
			}
		}
		return this;
	}

	public CacheControl deep(Collection<? extends CacheControl.Aware> ccas) {
		if (!validated) {
			if (ccas != null) {
				for (CacheControl.Aware cca : ccas) {
					if (cca != null) {
						cca.deepCheck(this);
					}
				}
			}
		}
		return this;
	}

	public CacheControl deep(Object... ccas) {
		if (!validated) {
			if (ccas != null) {
				for (Object cca : ccas) {
					if (cca != null) {
						if (cca instanceof Aware) {
							((Aware) cca).deepCheck(this);
						} else {
							etag(cca);
						}
					}
				}
			}
		}
		return this;
	}

	public CacheControl etag(Object... keyParts) {
		if (!validated) {
			for (Object keyPart : keyParts) {
				if (etagCache.remaining() < 4) {
					if (md == null) {
						try {
							md = MessageDigest.getInstance("MD5");
						} catch (NoSuchAlgorithmException e) {
							throw new AssertionError(e.toString());
						}
					}
					md.update(etagCache);
					etagCache.clear();
				}

				if (keyPart != null) {
					etagCache.putInt(keyPart.hashCode());
				} else {
					etagCache.put((byte) 83);
				}
			}
		}
		return this;
	}

	public CacheControl expires(Date expires) {
		return expires(expires != null ? expires.getTime() : -1L);
	}

	public CacheControl expires(long expires) {
		if (!validated) {
			this.expires = expires;
		}
		return this;
	}

	public CacheControl lastModified(Date date) {
		if (!validated) {
			if (date != null) {
				lastModified(date != null ? date.getTime() : -1L);
			}
		}
		return this;
	}

	public CacheControl lastModified(long time) {
		if (!validated) {
			if (time > lastModified) {
				this.lastModified = time;
			}
		}
		return this;
	}

	public CacheControl maxAge(int seconds) {
		if (!validated) {
			this.maxAge = seconds;
		}
		return this;
	}

	public CacheControl mustRevalidate() {
		if (!validated) {
			this.mustRevalidate = true;
		}
		return this;
	}

	public CacheControl noCache() {
		if (!validated) {
			noCache = true;
			disableNotModified = true;
		}
		return this;
	}

	public CacheControl noStore() {
		if (!validated) {
			noStore = true;
			disableNotModified = true;
		}
		return this;
	}

	public CacheControl noTransform() {
		if (!validated) {
			this.noTransform = true;
		}
		return this;
	}

	public CacheControl sMaxAge(int seconds) {
		if (!validated) {
			this.sMaxAge = seconds;
		}
		return this;
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(256);

		// Add request cache headers
		b.append("CacheControl( request(");
		boolean first = true;
		for (Header h : Header.values()) {
			String v = request.getHeader(h.toString());
			if (v != null) {
				if (!first) {
					b.append("; ");
				}
				b.append(h).append(": ").append(v);
				first = false;
			}
		}
		// Add response cache headers
		b.append("), response(");
		first = true;
		for (Header h : Header.values()) {
			String v = response.getHeader(h.toString());
			if (v != null) {
				if (!first) {
					b.append("; ");
				}
				b.append(h).append(": ").append(v);
				first = false;
			}
		}
		b.append(") )");
		return b.toString();
	}

	public void validate() throws NotModified {
		// Prevent double validation
		if (validated) {
			return;
		}

		validated = true;

		StringBuilder cc = new StringBuilder();
		long expires = this.expires;

		if (mustRevalidate) {
			comma(cc).append("must-revalidate");
		} else if (maxAge >= 0) {
			comma(cc).append("max-age=").append(maxAge);
			if (expires < 0) {
				expires = System.currentTimeMillis() + maxAge * 1000L;
			}
		}

		if (proxyRevalidate) {
			comma(cc).append("proxy-revalidate");
		} else if (sMaxAge >= 0) {
			comma(cc).append("s-max-age=").append(sMaxAge);
			if (expires < 0) {
				expires = System.currentTimeMillis() + sMaxAge * 1000L;
			}
		}

		if (cachePrivate) {
			comma(cc).append("private");
		} else if (cachePublic) {
			comma(cc).append("public");
		}

		if (noStore) {
			comma(cc).append("no-store");
		} else if (noCache) {
			comma(cc).append("no-cache");
		}

		if (expires < 0 && (noCache || noStore)) {
			expires = System.currentTimeMillis();
		}

		if (noTransform) {
			comma(cc).append("no-transform");
		}

		// Set response headers
		if (expires > 0) {
			response.setDateHeader(Header.EXPIRES.toString(), expires);
		}

		if (lastModified > 0) {
			response.setDateHeader(Header.LAST_MODIFIED.toString(), lastModified);
		}

		if (cc.length() > 0) {
			response.setHeader(Header.CACHE_CONTROL.toString(), cc.toString());
		}

		if (vary != null) {
			response.setHeader(Header.VARY.toString(), vary.toString());
		}

		if (noCache || noStore) {
			response.setHeader(Header.PRAGMA.toString(), "no-cache");
		}

		// Check ETag (If-None-MATCH)
		if (md != null || etagCache.position() > 0) {
			if (md == null) {
				try {
					md = MessageDigest.getInstance("MD5");
				} catch (NoSuchAlgorithmException e) {
					throw new AssertionError(e.toString());
				}
			}
			etagCache.flip();
			md.update(etagCache);

			String token = new BigInteger(1, md.digest()).toString(16);

			// Clear fields
			this.md = null;
			this.etagCache = null;

			response.setHeader(Header.ETAG.toString(), '"' + token + '"');
			if (!disableNotModified) {
				String previousToken = strip(request.getHeader(Header.IF_NONE_MATCH.toString()), "\" ");
				if (previousToken != null && token.equals(previousToken)) {
					throw new NotModified();
				}
				disableNotModified = true;
			}
		}

		// Check last modified (If-Modified-Since)
		try {
			long previousLastModified = request.getDateHeader(Header.IF_MODIFIED_SINCE.toString());
			if (!disableNotModified) {
				if (lastModified > 0 && lastModified <= previousLastModified) {
					throw new NotModified();
				}
			}
		} catch (IllegalArgumentException ignored) {
		}
	}

	public CacheControl vary(String header) {
		if (!validated) {
			if (this.vary == null) {
				this.vary = new StringBuilder();
			}
			if (header != null) {
				comma(this.vary).append(header);
			} else {
				this.vary.setLength(0);
			}
		}
		return this;
	}

	private StringBuilder comma(StringBuilder b) {
		if (b.length() > 0) {
			b.append(", ");
		}
		return b;
	}

	private boolean cachePrivate;
	private boolean cachePublic;
	private boolean disableNotModified;
	private ByteBuffer etagCache;
	private long expires = -1L;
	private long lastModified = -1L;
	private int maxAge = -1;
	private MessageDigest md;
	private boolean mustRevalidate;
	private boolean noCache;
	private boolean noStore;
	private boolean noTransform;
	private boolean proxyRevalidate;
	private HttpServletRequest request;
	private HttpServletResponse response;
	private int sMaxAge = -1;
	private boolean validated;
	private StringBuilder vary;
}
