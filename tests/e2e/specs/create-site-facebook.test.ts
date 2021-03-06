/// <reference path="../test-types.ts"/>

import _ = require('lodash');
import assert = require('assert');
import server = require('../utils/server');
import utils = require('../utils/utils');
import pages = require('../utils/pages');
import pagesFor = require('../utils/pages-for');
import settings = require('../utils/settings');
import logAndDie = require('../utils/log-and-die');
import createTestData = require('./create-site-impl');
const logUnusual = logAndDie.logUnusual, die = logAndDie.die, dieIf = logAndDie.dieIf;
const logMessage = logAndDie.logMessage;

declare let browser: any;

const newMembersEmail = 'e2e-test--mia@example.com';
const newMembersTopicTitle = 'newMembersTopicTitle';
const newMembersTopicText = 'newMembersTopicText';

describe('create-site-facebook  @createsite @login @facebook  TyT8KA9AW3', () => {

  if (!settings.include3rdPartyDependentTests) {
    console.log("Skipping this spec; no 3rd party login credentials specified.");
    return;
  }

  it('initialize', () => {
    browser = _.assign(browser, pagesFor(browser));
  });

  it('can create a new site as a Facebook user, when not logged in to FB', () => {
    makeForumWithFacebookAdminAccount();
  });

  it('can actually use the FB admin account to create stuff', () => {
    pages.complex.createAndSaveTopic({ title: "Facebook topic title", body: "Body" });
    pages.topbar.clickLogout(); // (6HRWJ3)
  });

  it('can create a new site as Facebook user, when already logged in to FB', () => {
    // Now we're logged in already, so the Facebook login flow is / might-be slightly different.
    makeForumWithFacebookAdminAccount();
    pages.topbar.clickLogout(); // (6HRWJ3)
  });

  function makeForumWithFacebookAdminAccount() {
    const data = createTestData();
    data.email = settings.facebookAdminEmail;
    data.password = settings.facebookAdminPassword;
    browser.go(utils.makeCreateSiteWithFakeIpUrl());
    browser.disableRateLimits(); // there're signup rate limits
    pages.createSite.fillInFieldsAndSubmit(data);
    pages.createSite.clickOwnerSignupButton();
    pages.loginDialog.createFacebookAccount(data, true);
    pages.createSomething.createForum("Facebook Forum Title");
  }

});

