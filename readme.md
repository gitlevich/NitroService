## Configuration

Before you can run the service, you need to add to the classpath a new file with name `application.conf` and the following content:

`nitro = {
    fetcher = {
    ws = {
      api_key = "the-actual-api-key"
    }
  }
}`

Please replace "the-actual-api-key" with your API key. 

If you care to see application logs, also add this fragment:

`akka {
   loglevel = "INFO"
 }`
 
The channels from which the app will extract schedule information is listed in channels.txt file.