#### _Try and YARN in less then 5_
This project is an R&D effort (for now) with the main goal of defining and discovering a **simpler (developer friendly) ways** of using YARN, allowing end user to concentrate on
the functional aspects of an application while delegating infrastructure and boilerplate code dealing with YARN internals to the framework. 

### YARN could and should be as simple as:

_**Unix command Application Container:**_
```
YarnApplication<Void> yarnApplication = YarnAssembly.forApplicationContainer("ping -c 4 yahoo.com").
										containerCount(4).
										withApplicationMaster().
												build("Simplest-Yarn-Command-Application");
		
yarnApplication.launch();
```
. . . or

_**Java process Application Container:**_
```
YarnConfiguration yarnConfiguration = new YarnConfiguration();
YarnApplication<Void> yarnApplication = 
	YarnAssembly.forApplicationContainer(ReverseMessageContainer.class, ByteBuffer.wrap("Hello Yarn!".getBytes())).
						containerCount(4).
						withApplicationMaster(yarnConfiguration).
							build("Simplest-Yarn-Java-Application");
		
yarnApplication.launch();

. . .

public static class ReverseMessageContainer implements ApplicationContainer {
		@Override
		public ByteBuffer process(ByteBuffer inputMessage) {
			inputMessage.rewind();
			byte[] inputBytes = new byte[inputMessage.limit()];
			inputMessage.get(inputBytes);
			String reversedMessage = new StringBuilder(new String(inputBytes)).reverse().toString();
			System.out.println("Processing input: " + reversedMessage);
			return null;
			// You can also return ByteBuffer, but since its a single task container
			// the contents of the returned ByteBuffer will be logged.
			//return ByteBuffer.wrap(strMessage.getBytes());
		}
}
```

##### [Introduction](https://github.com/olegz/yarn-tutorial/wiki/Introduction)
##### [For Developers](https://github.com/olegz/yarn-tutorial/wiki/Developers)
##### [Core Features](https://github.com/olegz/yarn-tutorial/wiki/CoreFeatures)
##### [Road Map](https://github.com/olegz/yarn-tutorial/wiki/Road Map)

**_This is an evolving work in progress so more updates (code and documentation) will be coming soon_**

_Please send question and updates via pull requests and/or raising [issues](https://github.com/olegz/yarn-tutorial/issues) on this project._