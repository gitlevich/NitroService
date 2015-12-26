## Configuration

Before you can run the service, you need to create the file `application.conf` on the classpath and put there the following incantation:

`nitro = {
    fetcher = {
    ws = {
      api_key = "the-actual-api-key"
    }
  }
}`

`"the-actual-api-key"` needs to be replaced with your actual API key. 

If you care to see the application log written on console, also add this fragment:

`akka {
   loglevel = "INFO"
 }`
 
The channels for which the app will extract schedules are listed in `channels.txt` file.
