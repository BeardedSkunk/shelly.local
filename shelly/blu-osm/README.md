# blu-osm

The script that publishes a Shelly BLU H&T to openSenseMap, as a template.

It is checked in with placeholders rather than with values:

    {{OSM_URL}}          the box's data endpoint
    {{OSM_TOKEN}}        that box's access token
    {{OSM_TEMPERATURE}}  the box's temperature sensor id
    {{OSM_HUMIDITY}}     its humidity sensor id

The app fills them in when it deploys, from the box the user picked out of
their own account. That is the whole reason for the template: a token belongs
to one box and to one person, and a copy of it checked in here would be a copy
of it in every build of the app and in the repository's history.

`app/src/main/assets/blu-osm.js` is a copy of this file and is what the app
actually uploads. Keep the two in step.
