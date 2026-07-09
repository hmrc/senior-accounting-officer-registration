
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

### Viewing the Schema

The schema is served from `http://localhost:10059/swagger`.

You can view the docs by:
* running `sm2 -start DEVHUB_PREVIEW_OPENAPI`
* accessing http://localhost:9680/api-documentation/docs/openapi/preview in a web browser
* Providing the URL `http://localhost:10059/swagger` and submitting the form

## License

This code is open source software licensed under the [Apache 2.0 License]("http://www.apache.org/licenses/LICENSE-2.0.html").
