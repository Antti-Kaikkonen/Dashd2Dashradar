Archived because I'm not going to maintain this repository anymore.

# Dashd2Dashradar
Fetches blocks one by one from [Dash Core](https://github.com/dashpay/dash) and saves them to a [Neo4j](https://github.com/neo4j/neo4j) graph database. 

## Dependencies

* https://github.com/Antti-Kaikkonen/DashdHttpConnector
* https://github.com/Antti-Kaikkonen/DashradarBackend

## Configuration

rename [application.properties.example](application.properties.example
) to application.properties

* rpcuser: rpc username of Dash Dore (found in dash.conf)
* rpcpassword: rpc password of Dash Core (found in dash.conf)
* rpcurl: URL where Dash Core is running
* neo4j.URI: URL to Neo4j database
