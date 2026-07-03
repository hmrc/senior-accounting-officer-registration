
# senior-accounting-officer-registration

Backend microservice for Senior Accounting Officer registration.

The service exposes protected registration endpoints and orchestrates sign-up requests with downstream services.

## Running the service

Start the service locally with:

```bash
sbt run
```

By default the service runs on port `9000`.

## Tests

Run the unit tests with:

```bash
sbt test
```

Run formatting and lint checks with:

```bash
sbt lint
```

## OpenApi Schema

This repository utilises [play-swagger](https://github.com/play-swagger/play-swagger) to generate an open api specification using a code-first approach.

The schema is generated upon running `sbt run`. It can be found in `/target/swagger/swagger.json` from the root of the repository.

### License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
