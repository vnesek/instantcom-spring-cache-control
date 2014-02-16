instantcom-spring-cache-control
=================================================

HTTP Cache-Control/ETag/Expires/Last-Modified/Vary header implementation for Spring WebMVC.
Easy to use fluent style API allowing fine control over generated headers. 

See http://www.mobify.com/blog/beginners-guide-to-http-cache-headers/

How to use it
-------------
Declare CacheControl as an argument to controller method and add CacheControl.NotModified to a list of exceptions. Use fluent API calls to set Cache-Control specific and call validate() to check against HTTP request. If nothing has changed, CacheControl::validate() will throw NotModified runtime exception which gets translated into 304 Not Modified HTTP response. Code after validate call is shortcuted including any view rendering.

ETag is calculated as an MD5 checksum of hash codes of passed objects. If object need a finer control over what will be used for etag calculation, it can implement CacheControl.Aware inteface and explicity call CacheControl::etag() with parameters that vary.

In addition to ETag and Cache-Control headers, you can also set Last-Modified, and Vary headers. Expires and Pragma: no-cache will be inferred if not explicitly set.

* Use CacheControl.Aware for fine control over headers:

```java
	@RequestMapping("/image/{id}")
	public View getImage(@PathVariable("id") Long id, @RequestParam(value = "s", required = false) Integer size,
			CacheControl cc) throws NotModified {
			
		// Fetch an image
		Image image = imageService.getImage(id, size);
		
		// Image implements CacheControl.Aware and set's ETag and Last-Modified
		// Last call in chain (validate()) would throw NotModifed if client's cache is up-to-date
        cc.deep(image).cachePublic().maxAge(24 * 3600).validate();
		
		// This will be skipped in image wasn't modified
		return new ImageView(image);
	}
	
	public class Image implements CacheControl.Aware {

		@Override
		public void deepCheck(CacheControl cc) {
			// Calculete ETag from image's id, width and height
			cc.etag(id, version, width, height);
			
			// Set HTTP Last-Modified to image's lastModified field
			cc.lastModified(lastModified);
		}

	}	
```

* Calculate ETag over a query objects (search, page) and result. Skip rendering if nothing is modified.

```java
	@RequestMapping("artists")
	public String artists(@ModelAttribute("search") ArtistSearch search, Pageable page, Map<String, Object> model,
			CacheControl cc) throws NotModified {
		List<Artist> result = browse.artists(search, page);

		cc.cachePrivate().maxAge(600).etag(search, page, results).validate();

		model.put("artists", result);
		return "artists";
	}
```

Building
--------
To produce a JAR you will need apache maven installed. Run:

> mvn clean package

... but all code is in a single .java file, so just copy it in your source tree.


Author contact and support
--------------------------
For any further information please contact Vjekoslav Nesek (vnesek@instantcom.net)
