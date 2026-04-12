<#import "layout/base.ftl" as layout>
<@layout.page title=title>
  <h2>Verifikationslista</h2>
  <p class="lead">Bokförda verifikationer i vald period med summerade debet- och kreditbelopp.</p>
  <ul class="summary">
    <#list summaryLines as line>
      <li>${line}</li>
    </#list>
  </ul>
  <table>
    <thead>
      <tr>
        <#list tableHeaders as header>
          <th>${header}</th>
        </#list>
      </tr>
    </thead>
    <tbody>
      <#list tableRows as row>
        <tr>
          <#list row as cell>
            <td>${cell}</td>
          </#list>
        </tr>
      </#list>
    </tbody>
  </table>
</@layout.page>
